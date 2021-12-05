package noobcash.actors

import java.security.MessageDigest
import java.sql.Timestamp

import akka.actor.{Actor, ActorLogging, Props}
import scala.annotation.tailrec

import noobcash.transaction.Transaction
import noobcash.noobchain.Block

object Miner {
  /**
    * Block mining request
    *
    * @param newBlockIndex: Long
    * @param previousHash: String
    * @param pendingTransactions: List[Transaction]
    */
  case class MineBlock(newBlockIndex: Long, previousHash: String, pendingTransactions: List[Transaction])

  /**
    * A block was successfully mined in miningTimeInMillis milliseconds
    *
    * @param newBlock: Block
    * @param miningTimeInMillis: Long
    */
  case class FoundBlock(newBlock: Block, miningTimeInMillis: Long)

  /**
    * Keep calculating the hash of a possible new block, until it has DIFFICULTY leading zeros,
    * by increasing the block's nonce by 1 for every failed attempt
    *
    * @param index: Long
    * @param timestamp: Timestamp
    * @param nonce: Int
    * @param previousHash: String
    * @param pendingTransactions: List[Transaction]
    * @param difficulty: Int
    * @return
    */
  private def mineBlock(index: Long, timestamp: Timestamp, nonce: Int, previousHash: String, pendingTransactions: List[Transaction], difficulty: Int): Block = {
    def calculateHash(nonce: Int): String =
      MessageDigest.getInstance("SHA-256")
        .digest((index.toString + pendingTransactions + timestamp + previousHash + nonce).getBytes("UTF-8"))
        .map("%02x".format(_))
        .mkString

    @tailrec
    def mineBlockRec(nonce: Int): Block =
      if(calculateHash(nonce).substring(0,difficulty) != List.fill(difficulty)("0").mkString) mineBlockRec(nonce + 1)
      else Block(index, timestamp, nonce, previousHash, pendingTransactions)

    mineBlockRec(nonce)
  }

  def props(DIFFICULTY: Int): Props = Props(new Miner(DIFFICULTY))
}

class Miner(DIFFICULTY: Int) extends Actor with ActorLogging {
  import Miner._

  override def receive: Receive = {
    case MineBlock(newBlockIndex, previousHash, pendingTransactions) =>
      log.info(s"Received new block-mining request for block $newBlockIndex, begin mining...")
      val miningStartTime: Long = System.currentTimeMillis()
      val finalBlock = mineBlock(newBlockIndex, new Timestamp(System.nanoTime()), 0, previousHash, pendingTransactions, DIFFICULTY)
      val miningEndTime: Long = System.currentTimeMillis()
      sender() ! FoundBlock(finalBlock, miningEndTime - miningStartTime)
  }
}
