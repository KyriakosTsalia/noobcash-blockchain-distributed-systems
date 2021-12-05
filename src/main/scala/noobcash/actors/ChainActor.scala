package noobcash.actors

import java.io.{File, FileWriter}
import java.security.{KeyPair, PublicKey}
import java.sql.Timestamp

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import noobcash.noobchain.{Block, Noobchain}
import noobcash.transaction.{Transaction, TransactionHttp, TransactionInput, TransactionOutput}

import scala.annotation.tailrec

object ChainActor {
  /**
    * Sent by the bootstrap's peer actor when the system gives the first NETWORK_SIZE * 100 NBC,
    * as a result the genesis block is created and the noobchain is initialized
    *
    * @param genesisTransaction: Transaction
    */
  case class InitializeNoobchain(genesisTransaction: Transaction)

  /**
    * Sent by the bootstrap's peer actor every time a new member is registered, a new association between
    * this new member's publicKey and an empty Set of UTXOs is made
    *
    * @param publicKey: Public
    */
  case class NewWalletWithUTXOs(publicKey: PublicKey)

  /**
    * Sent when the cluster has NETWORK_SIZE peers and the bootstrap wants to broadcast the initial state,
    * i.e. the bootstrap's initial UTXOs and the genesis block
    */
  case object StateBeforeInitialTransactionsRequest

  /**
    * Response to the StateBeforeInitialTransactionsRequest message
    *
    * @param initialUTXOs: Set[TransactionOutput]
    * @param initialNoobchain: Noobchain
    */
  case class StateBeforeInitialTransactionsResponse(initialUTXOs: Set[TransactionOutput], initialNoobchain: Noobchain)

  /**
    * Sent when a node receives the initial state by the bootstrap, initialNoobchain is the noobchain containing just the genesis block
    * and totalUTXOs are the complete publicKey -> Set[UTXOs] associations
    *
    * @param totalUTXOs: Map[PublicKey, Set[TransactionOutput]
    * @param initialNoobchain: Noobchain
    */
  case class SetStateBeforeInitialTransactions(totalUTXOs: Map[PublicKey, Set[TransactionOutput]], initialNoobchain: Noobchain)

  /**
    * Handled when the peer actor receives an HTTP transaction request message
    *
    * @param prettySender: String
    * @param prettyReceiver: String
    * @param senderAddress: PublicKey
    * @param receiverAddress: PublicKey
    * @param amount: Int
    */
  case class CreateTransaction(prettySender: String, prettyReceiver: String, senderAddress: PublicKey, receiverAddress: PublicKey, amount: Int)

  /**
    * Response to a CreateTransaction message when insufficient funds are found
    *
    * @param prettySender: String
    * @param prettyReceiver: String
    * @param amount: amount
    * @param message: String
    */
  case class TransactionFailure(prettySender: String, prettyReceiver: String, amount: Int, message: String)

  /**
    * Response to a CreateTransaction message when the transaction is successfully created,
    * signaling the peer to broadcast it to all peers
    *
    * @param prettySender: String
    * @param prettyReceiver: String
    * @param transaction: Transaction
    */
  case class TransactionSuccess(prettySender: String, prettyReceiver: String, transaction: Transaction)

  /**
    * Handled when the node received a new block successfully mined from another node, in order to check
    * if the new block can be added to the local blockchain
    *
    * @param newBlock: Block
    */
  case class ValidateBlock(newBlock: Block)

  /**
    * Handled when all peers had an equal length chain when asked to report their extra blocks
    */
  case object ConflictNotResolved

  /**
    * A local conflict was successfully resolved after broadcasting the request,
    * extraBlocks are the extra blocks the peer with the longest blockchain has that I need to add to the local blockchain,
    * after discarding the ones that are not common
    *
    * @param remoteChainLength: Long
    * @param extraBlocks: List[Block]
    */
  case class ConflictResolved(remoteChainLength: Long, extraBlocks: List[Block])

  /**
    * Handled when the node needs to respond to a ReportExtraBlocks request
    *
    * @param originalSender: ActorRef
    * @param remoteChainLength: Long
    * @param listOfRemoteBlockHashes: List[String]
    */
  case class PossibleExtraBlocks(originalSender: ActorRef, remoteChainLength: Long, listOfRemoteBlockHashes: List[String])

  /**
    * Response to a PossibleExtraBlocks request, when the local blockchain has the same length as the remote one
    *
    * @param toSomePeer: ActorRef
    */
  case class RespondNoExtraBlocks(toSomePeer: ActorRef)

  /**
    * Response to a PossibleExtraBlocks request, when the local blockchain is longer than the remote one,
    * extraBlocks are the local blockchain's blocks placed after the last common one
    *
    * @param toSomePeer: ActorRef
    * @param myChainLength: Long
    * @param extraBlocks: List[Block]
    */
  case class RespondWithExtraBlocks(toSomePeer: ActorRef, myChainLength: Long, extraBlocks: List[Block])

  /**
    * The peer received an HTTP ReportWallets request
    *
    * @param originalSender: ActorRef
    */
  case class SumWallets(originalSender: ActorRef)

  /**
    * Response to a SumWallets message, contains a mapping between every publicKey and the available NBC in its wallets
    *
    * @param wallets: Map[PublicKey, Int]
    * @param originalSender: ActorRef
    */
  case class Wallets(wallets: Map[PublicKey, Int], originalSender: ActorRef)

  def props(wallet: KeyPair, NETWORK_SIZE: Int, CAPACITY: Int, DIFFICULTY: Int): Props = Props(new ChainActor(wallet, NETWORK_SIZE, CAPACITY, DIFFICULTY))
}

class ChainActor(wallet: KeyPair, NETWORK_SIZE: Int, CAPACITY: Int, DIFFICULTY: Int) extends Actor with ActorLogging with Stash {
  import ChainActor._
  import Miner._
  import Peer._

  private val peer = context.parent
  private val miner = context.actorOf(Miner.props(DIFFICULTY), "miner")

  private val (publicKey, privateKey) = (wallet.getPublic, wallet.getPrivate)

  val blockTimekeepingFile = new File(s"${sys.env("HOME")}/blockMiningTimes_${NETWORK_SIZE}_${CAPACITY}_$DIFFICULTY.txt")
  val transactionTimekeepingFile = new File(s"${sys.env("HOME")}/transactionTimes_${NETWORK_SIZE}_${CAPACITY}_$DIFFICULTY.txt")

  /**
    * If the provided funds are found in the sender's wallets (UTXOs), it returns a tuple with the updated wallet
    * as well as the sender's change. If not, the initial wallet is returned and the sender's change has a value of -1,
    * indicating that the transaction attempt was unsuccessful
    *
    * @param funds: List[TransactionInput]
    * @param initialUTXOs: Set[TransactionOutput]
    * @param amountNeeded: Int
    * @param remainingUTXOs: Set[TransactionOutput]
    * @return
    */
  @tailrec
  private def removeProvidedFunds(funds: List[TransactionInput], initialUTXOs: Set[TransactionOutput], amountNeeded: Int, remainingUTXOs: Set[TransactionOutput]): (Set[TransactionOutput], Int) = {
    if (amountNeeded <= 0) (remainingUTXOs, -amountNeeded)
    else if (funds.isEmpty && remainingUTXOs.isEmpty) (initialUTXOs, -1)
    else {
      funds.headOption
        .map(_.UTXOId)
        .flatMap(fundId => remainingUTXOs.find(utxo => utxo.outputId == fundId)) match {
        case None => (initialUTXOs, -1)
        case Some(transactionOutput) =>
          removeProvidedFunds(funds.tail, initialUTXOs, amountNeeded - transactionOutput.amount, remainingUTXOs - transactionOutput)
      }
    }
  }

  /**
    * Returns an Option of a tuple of the IDs of the transactionOutputs to be used as funds and the total amount collected
    *
    * @param myUTXOs: Set[TransactionOutput]
    * @param amount: Int
    * @return
    */
  private def findFunds(myUTXOs: Set[TransactionOutput], amount: Int): Option[(Set[String], Int)] = {
    @tailrec
    def findFundsRec(UTXOs: Set[TransactionOutput], gatheredFunds: Set[String], amountLeft: Int): Option[(Set[String], Int)] = {
      if (amountLeft <= 0) Some(gatheredFunds, -amountLeft)
      else if (UTXOs.isEmpty) None
      else {
        val head: TransactionOutput = UTXOs.head
        findFundsRec(UTXOs - head, gatheredFunds + head.outputId, amountLeft - head.amount)
      }
    }

    findFundsRec(myUTXOs, Set(), amount)
  }

  /**
    * Returns the available balance of a given wallet in NBC
    *
    * @param UTXOs: Set[TransactionOutput]
    * @return
    */
  private def findBalance(UTXOs: Set[TransactionOutput]): Int = UTXOs.foldLeft(0)((sum, utxo) => sum + utxo.amount)

  /**
    * When CAPACITY transactions have been processed, a new block mining request is sent to the miner
    * and the context switches to waitingMinedBlock
    *
    * @param wallets: Map[PublicKey, Set[TransactionOutput] ]
    * @param noobchain: Noobchain
    * @param pendingTransactions: List[Transaction]
    * @param txsInLastXBlocks: Set[String]
    * @param gettingMined: List[Transaction]
    */
  private def beginMining(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Unit = {
    val latestBlock = noobchain.getLatestBlock
    val toMine = pendingTransactions.take(CAPACITY)
    miner ! MineBlock(latestBlock.index + 1, latestBlock.currentHash, toMine)
    context.become(waitingMinedBlock(wallets, noobchain, pendingTransactions diff toMine, txsInLastXBlocks, gettingMined ++ toMine))
  }

  /**
    * Depending on whether a block is getting mined or not, the context switches to waitingForMinedBlock or online respectively
    *
    * @param wallets: Map[PublicKey, Set[TransactionOutput] ]
    * @param noobchain: Noobchain
    * @param pendingTransactions: List[Transaction]
    * @param txsInLastXBlocks: Set[String]
    * @param gettingMined: List[Transaction]
    */
  private def maybeOnline(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Unit = {
    if (gettingMined.isEmpty)
      context.become(online(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined))
    else
      context.become(waitingMinedBlock(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined))
  }

  /**
    * Process the transactions that were not already processed, when a new block is validated or a conflict is resolved
    *
    * @param txsToProcess: List[Transaction]
    * @param wallets: Map[PublicKey, Set[TransactionOutput] ]
    * @return
    */
  @tailrec
  private def processExtraTransactions(txsToProcess: List[Transaction], wallets: Map[PublicKey, Set[TransactionOutput]]): Map[PublicKey, Set[TransactionOutput]] = {
    if (txsToProcess isEmpty) wallets
    else {
      val t = txsToProcess.head
      val updatedWallets = updateWallets(wallets, t.transactionId, t.senderAddress, t.receiverAddress, t.transactionInputs, t.amount)
      processExtraTransactions(txsToProcess.tail, updatedWallets)
    }
  }

  /**
    * Update wallets when a transaction is already accepted
    *
    * @param wallets: Map[PublicKey, Set[TransactionOutput] ]
    * @param tid: String
    * @param sender: PublicKey
    * @param receiver: PublicKey
    * @param funds: List[TransactionInput]
    * @param amount: Int
    * @return
    */
  private def updateWallets(wallets: Map[PublicKey, Set[TransactionOutput]], tid: String, sender: PublicKey, receiver: PublicKey, funds: List[TransactionInput], amount: Int): Map[PublicKey, Set[TransactionOutput]] = {
    val initialReceiverUTXOs = wallets(receiver)
    val initialSenderUTXOs = wallets(sender)

    val (interimSenderUTXOs, amountLeft) = removeProvidedFunds(funds, initialSenderUTXOs, amount, initialSenderUTXOs)

    val newSenderUTXOs =
      if (amountLeft > 0) interimSenderUTXOs + TransactionOutput(tid, sender, amountLeft)
      else interimSenderUTXOs
    val newReceiverUTXOs = initialReceiverUTXOs + TransactionOutput(tid, receiver, amount)

    wallets + (sender -> newSenderUTXOs) + (receiver -> newReceiverUTXOs)
  }

  /**
    * Possibly accept a transaction, only if the provided funds are found and are sufficient
    *
    * @param prettySender: String
    * @param prettyReceiver: String
    * @param t: Transaction
    * @param wallets: Map[PublicKey, Set[TransactionOutput] ]
    * @param noobchain: Noobchain
    * @param pendingTransactions: List[Transaction]
    * @param txsInLastXBlocks: Set[String]
    * @param gettingMined: List[Transaction]
    */
  private def maybeAcceptTransaction(prettySender: String, prettyReceiver: String, t: Transaction, wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Unit = {
    val initialSenderUTXOs = wallets(t.senderAddress)
    val initialReceiverUTXOs = wallets(t.receiverAddress)

    val (interimSenderUTXOs, amountLeft) = removeProvidedFunds(t.transactionInputs, initialSenderUTXOs, t.amount, initialSenderUTXOs)
    if (amountLeft < 0)
      log.warning(s"New transaction attempt from $prettySender to $prettyReceiver for ${t.amount} NBC had insufficient funds, discarding...")
    else {
      log.info(s"New transaction from $prettySender to $prettyReceiver for ${t.amount} NBC was successful, updating wallets...")
      val newSenderUTXOs =
        if (amountLeft > 0) interimSenderUTXOs + TransactionOutput(t.transactionId, t.senderAddress, amountLeft)
        else interimSenderUTXOs

      val newReceiverUTXOs = initialReceiverUTXOs + TransactionOutput(t.transactionId, t.receiverAddress, t.amount)
      val newWallets = wallets + (t.senderAddress -> newSenderUTXOs) + (t.receiverAddress -> newReceiverUTXOs)
      val newPendingTransactions = pendingTransactions :+ t
      context.become(online(newWallets, noobchain, newPendingTransactions, txsInLastXBlocks, gettingMined))

      if (newPendingTransactions.length >= CAPACITY)
        beginMining(newWallets, noobchain, newPendingTransactions, txsInLastXBlocks, gettingMined)
    }
  }

  override def receive: Receive = initially(Map((publicKey, Set())), Noobchain(Nil))

  def initially(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain): Receive =
    handleRequests(wallets, noobchain)
      .orElse(handleInitialState(wallets, noobchain))
      .orElse(handleOthers)

  def online(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Receive =
    handleRequests(wallets, noobchain)
      .orElse(handleRemoteConflictRequests(noobchain: Noobchain))
      .orElse(handleTransaction(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined))
      .orElse(handleBlock(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined))
      .orElse(handleOthers)

  def waitingMinedBlock(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Receive =
    handleRequests(wallets, noobchain)
      .orElse(handleRemoteConflictRequests(noobchain))
      .orElse(handleBlock(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined))
      .orElse(handleMining(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined))
      .orElse(handleOthers)

  def resolvingConflict(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Receive =
    handleRequests(wallets, noobchain)
      .orElse(handleRemoteConflictRequests(noobchain))
      .orElse(handleConflict(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined))
      .orElse(handleOthers)

  def handleRequests(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain): Receive = {
    case SumWallets(originalSender) => sender() ! Wallets(wallets.map(wallet => (wallet._1, findBalance(wallet._2))), originalSender)
    case Balance => sender() ! findBalance(wallets(publicKey))
    case View =>
      sender() ! noobchain.getLatestBlock.transactions.map {
        case Transaction(sa, ra, amount, tIns, _) => TransactionHttp(sa.toString, ra.toString, amount, tIns)
      }
  }

  def handleInitialState(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain): Receive = {
    case InitializeNoobchain(genesisTransaction) =>
      log.info("Received genesis transaction, initializing noobchain...")
      val transactionOutputForBootstrap = TransactionOutput(genesisTransaction.transactionId, publicKey, NETWORK_SIZE * 100)
      val genesisBlock = Block(0, new Timestamp(System.nanoTime()), 0, "1", List(genesisTransaction))
      val newWallets = Map(publicKey -> Set(transactionOutputForBootstrap))
      log.info(s"Initial wallet balance: ${findBalance(newWallets(publicKey))} NBC")
      context.become(initially(Map(publicKey -> Set(transactionOutputForBootstrap)), Noobchain(List(genesisBlock))))

    case NewWalletWithUTXOs(remotePublicKey) =>
      log.info("Received a new UTXO wallet, adding it to my wallets...")
      context.become(initially(wallets + (remotePublicKey -> Set()), noobchain))

    case StateBeforeInitialTransactionsRequest =>
      sender() ! StateBeforeInitialTransactionsResponse(wallets(publicKey), noobchain)
      context.become(online(wallets, noobchain, Nil, Set(), Nil))

    case SetStateBeforeInitialTransactions(totalUTXOs, initialNoobchain) =>
      log.info("Setting up initial state...")
      context.become(online(totalUTXOs, initialNoobchain, Nil, Set(), Nil))
  }

  def handleTransaction(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Receive = {
    case CreateTransaction(prettySender, prettyReceiver, senderAddress, receiverAddress, amount) =>
      findFunds(wallets(publicKey), amount) match {
        case None =>
          log.warning("Insufficient funds were found, discarding...")
          sender() ! TransactionFailure(prettySender, prettyReceiver, amount, "Not enough funds")

        case Some((funds, _)) =>
          val newTransaction = Transaction(senderAddress, receiverAddress, amount, funds.map(TransactionInput).toList)
            .signTransaction(publicKey, privateKey)

          log.info(s"Transaction from $prettySender to $prettyReceiver for $amount NBC was created, sending to peer for broadcasting...")
          peer ! TransactionSuccess(prettySender, prettyReceiver, newTransaction)

          val fw = new FileWriter(transactionTimekeepingFile, true)
          fw.write(s"${System.currentTimeMillis()}\n")
          fw.close()

          val newWallets = updateWallets(wallets, newTransaction.transactionId, senderAddress, receiverAddress, funds.map(TransactionInput).toList, amount)
          val newPendingTransactions = pendingTransactions :+ newTransaction
          context.become(online(newWallets, noobchain, newPendingTransactions, txsInLastXBlocks, gettingMined))

          if (newPendingTransactions.length >= CAPACITY)
            beginMining(newWallets, noobchain, newPendingTransactions, txsInLastXBlocks, gettingMined)
      }

    case NewTransaction(prettySender, prettyReceiver, newTransaction @ Transaction(_, _, amount, _, _)) =>
      log.info(s"Processing new transaction from $prettySender to $prettyReceiver for $amount NBC...")
      if (txsInLastXBlocks.contains(newTransaction.transactionId)) {
        log.info("One of my blocks already contains this transaction, discarding...")
      }
      else {
        if (!newTransaction.verifySignature)
          log.warning(s"New transaction attempt from $prettySender to $prettyReceiver for $amount NBC was invalid/not signed, discarding...")
        else maybeAcceptTransaction(prettySender, prettyReceiver, newTransaction, wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined)
      }
  }

  def handleBlock(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Receive = {
    case ValidateBlock(newBlock) =>
      log.info("Received a new block from my peer, validating...")
      val latestBlock = noobchain.getLatestBlock
      val isAccepted = newBlock.isValid(latestBlock) && newBlock.isNotCorrupted
      if (isAccepted) {
        log.info(s"New block with index ${newBlock.index} is valid and honest, updating my noobchain and wallets...")
        val newNoobchain = noobchain.addBlock(newBlock)

        val transactionsToProcess = (newBlock.transactions diff pendingTransactions diff gettingMined).filter(t => !txsInLastXBlocks(t.transactionId))
        val newPendingTransactions = pendingTransactions diff newBlock.transactions
        val newTxsInLastXBlocks = txsInLastXBlocks ++ newBlock.transactions.map(_.transactionId).toSet

        if (transactionsToProcess isEmpty) {
          log.info("I have already processed every transaction in this block, moving forward")
          maybeOnline(wallets, newNoobchain, newPendingTransactions, newTxsInLastXBlocks, gettingMined)
        }
        else {
          log.info("Updating my wallets with the transactions I had yet to process...")
          val newWallets = processExtraTransactions(transactionsToProcess, wallets)
          maybeOnline(newWallets, newNoobchain, newPendingTransactions, newTxsInLastXBlocks, gettingMined)
        }
      }
      else if (!newBlock.isNotCorrupted) log.warning(s"New block is corrupted, discarding...")
      else {
        log.warning(s"New block with index ${newBlock.index} is not valid, perhaps a chain fork has happened, resolving conflict...")
        peer ! ResolveConflict(noobchain.chain.length, noobchain.chain.map(_.currentHash))
        context.become(resolvingConflict(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined))
      }
  }

  def handleMining(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Receive = {
    case foundBlock @ FoundBlock(newBlock, miningTimeInMillis) =>
      val newGettingMined = gettingMined diff newBlock.transactions
      log.info(s"Received new block from my miner, validating...")
      val isAccepted = newBlock.isValid(noobchain.getLatestBlock) && newBlock.isNotCorrupted
      if (isAccepted) {
        log.info(s"New block with index ${newBlock.index} is accepted, sending to my peer for broadcasting...")
        val newNoobchain = noobchain.addBlock(newBlock)
        val newTxsInLastXBlocks = txsInLastXBlocks ++ newBlock.transactions.map(_.transactionId).toSet
        peer ! foundBlock

        val fw = new FileWriter(blockTimekeepingFile, true)
        fw.write(s"$miningTimeInMillis\n")
        fw.close()

        unstashAll()
        if (pendingTransactions.length >= CAPACITY)
          beginMining(wallets, newNoobchain, pendingTransactions, newTxsInLastXBlocks, newGettingMined)
        else
          context.become(online(wallets, newNoobchain, pendingTransactions, newTxsInLastXBlocks, newGettingMined))
      }
      else {
        // orphaned block, for every transaction that is not part of the latest blocks, put it in the pending again
        log.warning(s"New block with index ${newBlock.index} is orphaned, updating my pending transactions...")

        val newPendingTransactions = {
          def putAgain(initialPendTrans: List[Transaction], blockTrans: List[Transaction], txsInLastXBlocks: Set[String], acc: List[Transaction]): List[Transaction] =
            if (blockTrans.isEmpty) acc ++ initialPendTrans
            else {
              val t = blockTrans.head
              if (!txsInLastXBlocks.contains(t.transactionId)) putAgain(initialPendTrans, blockTrans.tail, txsInLastXBlocks, acc :+ t)
              else putAgain(initialPendTrans, blockTrans.tail, txsInLastXBlocks, acc)
            }

          putAgain(pendingTransactions, newBlock.transactions, txsInLastXBlocks, Nil)
        }

        unstashAll()

        // this if for when a block is orphaned because a conflict was resolved and now possibly another block is getting mined
        // newGettingMined.isEmpty is true when no other block is getting mined, so I can start mining
        // if I can't I wait for the mined block with the newPendingTransactions, which I will check again when the next block
        // is either accepted or orphaned
        if (newPendingTransactions.length >= CAPACITY) {
          if (newGettingMined.isEmpty)
            beginMining(wallets, noobchain, newPendingTransactions, txsInLastXBlocks, newGettingMined)
          else
            context.become(waitingMinedBlock(wallets, noobchain, newPendingTransactions, txsInLastXBlocks, newGettingMined))
        }
        else
          maybeOnline(wallets, noobchain, newPendingTransactions, txsInLastXBlocks, newGettingMined)
      }

    case m =>
      log.info(s"Stashing $m")
      stash()
  }

  def handleRemoteConflictRequests(noobchain: Noobchain): Receive = {
    case PossibleExtraBlocks(originalSender, remoteChainLength, listOfRemoteBlockHashes) =>
      val myChainLength = noobchain.chain.length
      if (remoteChainLength >= myChainLength) peer ! RespondNoExtraBlocks(originalSender)
      else {
        @tailrec
        def findExtraBlocks(chain: List[Block], listOfRemoteBlockHashes: List[String]): List[Block] = {
          if (listOfRemoteBlockHashes.isEmpty) chain.reverse
          else if (chain.head.currentHash != listOfRemoteBlockHashes.head) chain.reverse
          else findExtraBlocks(chain.tail, listOfRemoteBlockHashes.tail)
        }

        val extraBlocks = findExtraBlocks(noobchain.chain.reverse, listOfRemoteBlockHashes.reverse)
        peer ! RespondWithExtraBlocks(originalSender, myChainLength, extraBlocks)
      }
  }

  def handleConflict(wallets: Map[PublicKey, Set[TransactionOutput]], noobchain: Noobchain, pendingTransactions: List[Transaction], txsInLastXBlocks: Set[String], gettingMined: List[Transaction]): Receive = {
    case ConflictNotResolved =>
      log.info("Conflict was not resolved, my chain remains the same...")
      unstashAll()
      maybeOnline(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined)

    case ConflictResolved(remoteChainLength, extraBlocks) =>
      log.info(s"Received ${extraBlocks.length} extra blocks: $extraBlocks")
      @tailrec
      def areValidBlocks(extraBlocks: List[Block]): Boolean = extraBlocks match {
        case _ :: Nil => true
        case hd :: tl  if hd.isValid(tl.head) && hd.isNotCorrupted => areValidBlocks(tl)
        case _ => false
      }

      val toRemove: Int = (noobchain.chain.length - remoteChainLength + extraBlocks.length).toInt

      if (!areValidBlocks(extraBlocks :+ noobchain.remove(toRemove).chain.head)) {
        log.info("Conflict was not resolved, I received some invalid new blocks, discarding...")
        unstashAll()
        maybeOnline(wallets, noobchain, pendingTransactions, txsInLastXBlocks, gettingMined)
      }
      else {
        log.info("Conflict was resolved, updating my chain and wallets...")

        val newBlocksInChronologicalOrder = extraBlocks.reverse

        val allTransactionsInNewBlocks = newBlocksInChronologicalOrder.flatMap(_.transactions)
        val allTransactionsInRedundantBlocks = noobchain.take(toRemove).flatMap(_.transactions)

        val allMine = allTransactionsInRedundantBlocks ++ pendingTransactions ++ gettingMined
        val transactionsToProcess = allTransactionsInNewBlocks diff allMine
        val haveProcessedButNotInTheNewBlocks = allTransactionsInRedundantBlocks diff allTransactionsInNewBlocks

        val newWallets =
          if (transactionsToProcess.isEmpty) {
            log.info("I have already processed every transaction in this block, moving forward")
            wallets
          }
          else {
            log.info("Updating my wallets with the transactions I had yet to process...")
            processExtraTransactions(transactionsToProcess, wallets)
          }

        val newPendingTransactions = haveProcessedButNotInTheNewBlocks ++ pendingTransactions
        val safeNoobchain = noobchain.remove(toRemove)
        val newTxsInLastXBlocks = (allTransactionsInNewBlocks ++ safeNoobchain.chain.flatMap(_.transactions)).map(_.transactionId).toSet
        val newNoobchain = noobchain.remove(toRemove).addMultipleBlocks(extraBlocks)

        unstashAll()
        if (newPendingTransactions.length >= CAPACITY)
          beginMining(newWallets, newNoobchain, newPendingTransactions, newTxsInLastXBlocks, gettingMined)
        else
          maybeOnline(newWallets, newNoobchain, newPendingTransactions, newTxsInLastXBlocks, gettingMined)
      }

    case m =>
      log.info(s"Stashing $m")
      stash()
  }

  def handleOthers: Receive = {
    case m =>
      log.warning(s"Something is wrong, received $m")
  }
}
