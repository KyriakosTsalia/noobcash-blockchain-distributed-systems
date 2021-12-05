package noobcash.actors

import java.security.{KeyPair, PublicKey}

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorRef, Address, Identify, Props, Stash}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import noobcash.Wallet
import noobcash.noobchain.{Block, Noobchain}
import noobcash.transaction.{Transaction, TransactionOutput}

import scala.annotation.tailrec
import scala.concurrent.duration._

object Peer {
  /**
    * Sent only by the bootstrap node to every new member of the cluster, as a response to the ActorIdentity message,
    * gives them their unique id from 1 to NETWORK_SIZE-1 (0 is reserved for the bootstrap)
    *
    * @param id: Int
    */
  case class PublicKeyRequest(id: Int)

  /**
    * Handled only by the bootstrap node, adds the association between the new member id and its publicKey
    * to the wallets and sends the updated wallets to the new member and just the new wallet to all other members
    *
    * @param id: Int
    * @param publicKey: PublicKey
    */
  case class PublicKeyResponse(id: Int, publicKey: PublicKey)

  /**
    * Handled by every new member in the cluster, wallets are all currently known id-publicKey associations
    *
    * @param wallets: Map[String, PublicKey]
    */
  case class UpdateWallets(wallets: Map[String, PublicKey])

  /**
    * Handled by every member of the cluster (except the bootstrap) when there is a new member in the cluster
    *
    * @param walletInfo: (String, PublicKey)
    */
  case class NewWallet(walletInfo: (String, PublicKey))

  /**
    * Sent by the bootstrap node and handled by every other member of the cluster, informs the about
    * the initial UTXOs of the bootstrap and about the genesis block
    *
    * @param initialBootstrapUTXOs: Set[TransactionOutput]
    * @param initialNoobchain: Noobchain
    */
  case class StateBeforeInitialTransactions(initialBootstrapUTXOs: Set[TransactionOutput], initialNoobchain: Noobchain)

  /**
    * As soon as the bootstrap node broadcasts the initial state (UTXOs + genesis), the initial transactions are made
    */
  case object BeginInitialTransactions

  /**
    * The node is informed about a new transaction made by another node
    *
    * @param prettySender: String
    * @param prettyReceiver: String
    * @param transaction: Transaction
    */
  case class NewTransaction(prettySender: String, prettyReceiver: String, transaction: Transaction)

  /**
    * The node is informed about a new block successfully mined by another miner
    *
    * @param newBlock: Block
    */
  case class NewBlock(newBlock: Block)

  /**
    * A request is made by the chainActor to resolve a conflict, when a new block is invalid and
    * cannot be added to my blockchain, broadcasts a ReportExtraBlocks message
    *
    * @param chainLength: Long
    * @param listOfBlockHashes: List[String]
    */
  case class ResolveConflict(chainLength: Long, listOfBlockHashes: List[String])

  /**
    * Handled when some other node tries to resolve a conflict
    *
    * @param remoteChainLength: Long
    * @param listOfRemoteBlockHashes: List[String]
    */
  case class ReportExtraBlocks(remoteChainLength: Long, listOfRemoteBlockHashes: List[String])

  /**
    * Response to a ReportExtraBlocks message, when my chainActor compares the two chains and finds that
    * either the remote chain is longer or they have equal length
    */
  case object NoExtraBlocks

  /**
    * Response to a ReportExtraBlocks message, when my chainActor compares the two chains and finds that
    * the local chain is longer than the remote chain
    *
    * @param chainLength: Long
    * @param extraBlocks: List[Block]
    */
  case class AddBlocks(chainLength: Long, extraBlocks: List[Block])

  /**
    * Just for testing purposes, every node starts reading their "transactionsX.txt" file and making transactions
    */
  case object BeginTesting

  /**
    * HTTP transaction request message
    *
    * @param receiverId: String
    * @param amount: Int
    */
  case class TransactionRequest(receiverId: String, amount: Int)

  /**
    * Response to a TransactionRequest HTTP request, if sufficient funds are found initially.
    * Whether or not the transaction is actually made depends on if sufficient funds are found when
    * the request is finally processed
    */
  case object TransactionAttemptRecorded

  /**
    * Response to a TransactionRequest HTTP request, when
    * - insufficient funds are found at the time of the request,
    * - the receiverId is invalid
    * - the node requests a transaction to itself, effectively creating meaningless network traffic and resource waste
    */
  case object TransactionAttemptDiscarded

  /**
    * HTTP report all wallets request - for debugging/making sure everything works correctly during the testing transactions
    */
  case object ReportWallets

  /**
    * HTTP wallet balance request
    */
  case object Balance

  /**
    * HTTP view transactions contained in the last validated block
    */
  case object View

  def props(wallet: KeyPair, NETWORK_SIZE: Int, CAPACITY: Int, DIFFICULTY: Int): Props = Props(new Peer(wallet, NETWORK_SIZE, CAPACITY, DIFFICULTY))
}

class Peer(wallet: KeyPair, NETWORK_SIZE: Int, CAPACITY: Int, DIFFICULTY: Int) extends Actor with ActorLogging with Stash {
  import ChainActor._
  import Miner._
  import Peer._
  import context.dispatcher
  implicit val timeout: Timeout = Timeout(2 seconds)

  val cluster = Cluster(context.system)

  override def preStart(): Unit =
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])

  override def postStop(): Unit =
    cluster.unsubscribe(self)

  private val chainActor = context.actorOf(ChainActor.props(wallet, NETWORK_SIZE, CAPACITY, DIFFICULTY), "chainActor")
  private val (publicKey, _) = (wallet.getPublic, wallet.getPrivate)

  if (cluster.selfMember.hasRole("bootstrap")) {
    log.info("Generating system wallet for the Genesis block...")
      val (systemPublicKey, systemPrivateKey) = {
        val systemWallet = Wallet()
        (systemWallet.getPublic, systemWallet.getPrivate)
      }

    val genesisTransaction = Transaction(
      senderAddress = systemPublicKey,
      receiverAddress = publicKey,
      amount = 100 * NETWORK_SIZE,
      transactionInputs = List(),
    ).signTransaction(systemPublicKey, systemPrivateKey)

    log.info("Initial transaction from the system to the bootstrap node was made...")
    chainActor ! InitializeNoobchain(genesisTransaction)
  }

  var nextId = 1
  var myId = 0
  var initialTransactionsLeft: Int = NETWORK_SIZE - 1
  var transIter: Iterator[String] = _

  override def receive: Receive = initially(Map((cluster.selfMember.address, self)), Map("0" -> publicKey))

  def initially(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey]): Receive =
    handleRequests(wallets)
      .orElse(handleClusterEvents)
      .orElse(handleMemberRegistration(peers, wallets))
      .orElse(handleWalletUpdates(peers, wallets))
      .orElse(handleInitialState(peers, wallets))
      .orElse(handleTransaction(peers, wallets))
      .orElse(handleBlocks(peers))
      .orElse(handleRemoteConflictRequests)
      .orElse(handleLocalConflictRequests(peers, wallets))

  def online(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey]): Receive =
    handleRequests(wallets)
      .orElse(handleClusterEvents)
      .orElse(handleMemberRegistration(peers, wallets))
      .orElse(handleTransaction(peers, wallets))
      .orElse(handleBlocks(peers))
      .orElse(handleRemoteConflictRequests)
      .orElse(handleLocalConflictRequests(peers, wallets))

  def resolvingConflict(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey], waitingFor: Set[ActorRef], myChainLength: Long, maxChainLength: Long, curExtraBlocks: List[Block]): Receive =
    handleRequests(wallets)
      .orElse(handleClusterEvents)
      .orElse(handleRemoteConflictRequests)
      .orElse(handleConflict(peers, wallets, waitingFor, myChainLength, maxChainLength, curExtraBlocks))

  def handleClusterEvents: Receive = {
    case MemberJoined(member) =>
      log.info(s"New peer in town: ${member.address}")

    case MemberUp(member) =>
      log.info(s"Member is up: ${member.address}")
      if (member != cluster.selfMember) {
        val peerSelection = context.actorSelection(s"${member.address}/user/peer")
        peerSelection ! Identify(member.address)
      }

    case UnreachableMember(member) =>
      log.info(s"Uh oh, peer ${member.address} is unreachable")
      context.stop(self)

    case m: MemberEvent =>
      log.info(s"Another member event: $m")
  }

  def handleRequests(wallets: Map[String, PublicKey]): Receive = {
    case BeginTesting =>
      log.info("Beginning testing...")
      transIter = scala.io.Source.fromFile(s"${sys.env("HOME")}/${NETWORK_SIZE}nodes/transactions$myId.txt").getLines()
      findTransaction(wallets)

    case ReportWallets => (chainActor ? SumWallets(sender())).mapTo[Wallets].pipeTo(self)

    case Wallets(sumWallets, originalSender) =>
      originalSender ! sumWallets.flatMap(wallet => wallets.find(_._2 == wallet._1).map { case (id, _) => s"id$id" ->wallet._2})

    case Balance => chainActor forward Balance

    case View => chainActor forward View

    case TransactionRequest(receiverId, amount) =>
      if (myId.toString == receiverId) sender() ! TransactionAttemptDiscarded
      else wallets.get(receiverId) match {
        case Some(receiverPublicKey) =>
          chainActor ! CreateTransaction(s"id$myId", s"id$receiverId", publicKey, receiverPublicKey, amount)
          sender() ! TransactionAttemptRecorded

        case None => sender() ! TransactionAttemptDiscarded
      }

  }

  def handleMemberRegistration(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey]): Receive = {
    case actorIdentity @ ActorIdentity(remoteAddress: Address, Some(remoteActorRef)) =>
      log.info(s"Received a new peer $actorIdentity")
      val newPeers = peers + (remoteAddress -> remoteActorRef)
      if (cluster.selfMember.hasRole("bootstrap") && remoteActorRef != self) {
        remoteActorRef ! PublicKeyRequest(nextId)
        nextId = nextId + 1
      }
      context.become(initially(newPeers, wallets))

    case PublicKeyRequest(id) =>
      myId = id
      log.info(s"My unique id is $id, sending my publicKey to the bootstrap node")
      sender() ! PublicKeyResponse(id, publicKey)

    case PublicKeyResponse(id, remotePublicKey) =>
      log.info(s"Received a publicKey from ${sender()}")
      val newWallets = wallets + (id.toString -> remotePublicKey)
      chainActor ! NewWalletWithUTXOs(remotePublicKey)

      peers.values.foreach { peer =>
        if (peer != self) {
          if (peer != sender()) peer ! NewWallet((id.toString, remotePublicKey))
          else peer ! UpdateWallets(newWallets)
        }
      }

      if (newWallets.size == NETWORK_SIZE) chainActor ! StateBeforeInitialTransactionsRequest
      context.become(initially(peers, newWallets))
  }

  def handleWalletUpdates(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey]): Receive = {
    case UpdateWallets(remoteWallets) =>
      log.info(s"Received current ${remoteWallets.size} wallets from the bootstrap node...")
      context.become(initially(peers, remoteWallets))

    case NewWallet((nodeId, nodePublicKey)) =>
      log.info("Received a new wallet from the bootstrap node...")
      context.become(initially(peers, wallets + (nodeId -> nodePublicKey)))
  }

  def handleInitialState(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey]): Receive = {
    case StateBeforeInitialTransactionsResponse(initialUTXOs, initialNoobchain) =>
      log.info("Broadcasting initial state...")
      peers.values.foreach { peer =>
        if (peer != self) peer ! StateBeforeInitialTransactions(initialUTXOs, initialNoobchain)
      }
      self ! BeginInitialTransactions

    case BeginInitialTransactions =>
      wallets.foreach { case (id, remotePublicKey) =>
        if (publicKey != remotePublicKey) chainActor ! CreateTransaction("id0", s"id$id", publicKey, remotePublicKey, 100)
      }
      context.become(initially(peers, wallets))

    case StateBeforeInitialTransactions(initialBootstrapUTXOs, initialNoobchain) =>
      log.info("Received initial state from the bootstrap node, informing my chain actor...")
      val walletsWithUTXOs = wallets.map(t => (t._2, Set[TransactionOutput]()))+ (wallets("0") -> initialBootstrapUTXOs)
      chainActor ! SetStateBeforeInitialTransactions(walletsWithUTXOs, initialNoobchain)
      context.become(initially(peers, wallets))
  }

  @tailrec
  private def findTransaction(wallets: Map[String, PublicKey]): Unit = {
    if (transIter != null && transIter.hasNext) {
      val t = transIter.next()
      val values = t.split(" ")
      val prettyReceiver = values(0)
      val amount = values(1).toInt
      wallets.get(prettyReceiver.substring(2)) match {
        case None => findTransaction(wallets)
        case Some(receiverPublicKey) => chainActor ! CreateTransaction(s"id$myId", prettyReceiver, publicKey, receiverPublicKey, amount)
      }
    }
  }

  def handleTransaction(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey]): Receive = {
    case newTransaction: NewTransaction =>
      log.info("Received a new transaction, sending it to my chainActor for processing...")
      chainActor ! newTransaction

    case TransactionFailure(prettySender, prettyReceiver, amount, message) =>
      log.warning(s"Failed to create transaction from $prettySender to $prettyReceiver for $amount NBC due to: $message")
      Thread.sleep(50) // small delay to prevent running out of transactions
      findTransaction(wallets)

    case TransactionSuccess(prettySender, prettyReceiver, newTransaction) =>
      log.info(s"Broadcasting newly created transaction from $prettySender to $prettyReceiver for ${newTransaction.amount} NBC...")
      peers.values.foreach(peer => if (peer != self) peer ! NewTransaction(prettySender, prettyReceiver, newTransaction))

      if (cluster.selfMember.hasRole("bootstrap")) {
        if (initialTransactionsLeft < 0) findTransaction(wallets)
      }
      else {
        findTransaction(wallets)
      }

      if (cluster.selfMember.hasRole("bootstrap") && initialTransactionsLeft > 0) {
        initialTransactionsLeft -= 1
        if (initialTransactionsLeft == 0) {
          peers.values.foreach(_ ! BeginTesting)
          initialTransactionsLeft = -1
        }
      }
  }

  def handleBlocks(peers: Map[Address, ActorRef]): Receive = {
    case FoundBlock(newBlock, _) =>
      log.info(s"Received a new block from my chainActor, broadcasting...")
      peers.values.foreach(peer => if (peer != self) peer ! NewBlock(newBlock))

    case NewBlock(newBlock) =>
      log.info(s"Received a new block from a peer, sending to my chainActor for validation...")
      chainActor ! ValidateBlock(newBlock)
  }

  def handleRemoteConflictRequests: Receive = {
    case ReportExtraBlocks(remoteChainLength, listOfRemoteBlockHashes) =>
      chainActor ! PossibleExtraBlocks(sender(), remoteChainLength, listOfRemoteBlockHashes)

    case RespondNoExtraBlocks(toSomePeer) =>
      log.info("I am responding to a remote conflict request with no extra blocks")
      toSomePeer ! NoExtraBlocks

    case RespondWithExtraBlocks(toSomePeer, myCurrentChainLength, extraBlocks) =>
      log.info(s"I am responding to a remote conflict request with my ${extraBlocks.length} extra blocks")
      toSomePeer ! AddBlocks(myCurrentChainLength, extraBlocks)
  }

  def recordConflictResponse(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey], originalSender: ActorRef, waitingFor: Set[ActorRef], myChainLength: Long, maxChainLength: Long, curExtraBlocks: List[Block]): Unit = {
    val newWaitingFor = waitingFor - originalSender
    if (newWaitingFor.nonEmpty)
      context.become(resolvingConflict(peers, wallets, newWaitingFor, myChainLength, maxChainLength, curExtraBlocks))
    else {
      if (myChainLength == maxChainLength) chainActor ! ConflictNotResolved
      else chainActor ! ConflictResolved(maxChainLength, curExtraBlocks)
      unstashAll()
      context.become(online(peers, wallets))
    }
  }

  def handleConflict(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey], waitingFor: Set[ActorRef], myChainLength: Long, maxChainLength: Long, curExtraBlocks: List[Block]): Receive = {
    case NoExtraBlocks =>
      if (waitingFor.contains(sender())) {
        log.info("A peer has either a same length or shorter chain than me, waiting for the other peers...")
        recordConflictResponse(peers, wallets, sender(), waitingFor, myChainLength, maxChainLength, curExtraBlocks)
      }

    case AddBlocks(remoteChainLength, remoteExtraBlocks) =>
      if (waitingFor.contains(sender())) {
        if (remoteChainLength > maxChainLength) {
          log.info("A peer with a longer chain was found, updating my extra blocks, waiting for the other peers...")
          recordConflictResponse(peers, wallets, sender(), waitingFor, myChainLength, remoteChainLength, remoteExtraBlocks)
        }
        else {
          log.info("A peer has either a same length or shorter chain than me, waiting for the other peers...")
          recordConflictResponse(peers, wallets, sender(), waitingFor, myChainLength, maxChainLength, curExtraBlocks)
        }
      }

    case m =>
      log.info(s"Stashing $m")
      stash()
  }

  def handleLocalConflictRequests(peers: Map[Address, ActorRef], wallets: Map[String, PublicKey]): Receive = {
    case ResolveConflict(myChainLength, listOfBlockHashes) =>
      log.info("Resolving conflict...")
      peers.values.foreach { peer =>
        if (peer != self) peer ! ReportExtraBlocks(myChainLength, listOfBlockHashes)
      }
      context.become(resolvingConflict(peers, wallets, peers.values.filter(_ != self).toSet, myChainLength, myChainLength, Nil))

    case m =>
      log.warning(s"Something is wrong, received message $m")
  }
}