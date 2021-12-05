package noobcash.noobchain

import java.security.MessageDigest
import java.sql.Timestamp

import scala.annotation.tailrec
import noobcash.transaction.Transaction

final case class Block(index: Long, timestamp: Timestamp, nonce: Int, previousHash: String, transactions: List[Transaction]) {
  def calculateHash(): String =
    MessageDigest.getInstance("SHA-256")
      .digest((index.toString + transactions + timestamp + previousHash + nonce).getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

  lazy val currentHash: String = calculateHash()

  def hasValidTransactions: Boolean = {
    @tailrec
    def hasValidTransactionsRec(transactions: List[Transaction]): Boolean = transactions match {
      case Nil => true
      case hd :: _ if !hd.verifySignature => false
      case _ :: tl => hasValidTransactionsRec(tl)
    }
    hasValidTransactionsRec(transactions)
  }

  def isValid(tlhd: Block): Boolean = this.previousHash == tlhd.currentHash && this.index == tlhd.index + 1

  def isNotCorrupted: Boolean = this.currentHash == this.calculateHash() && this.hasValidTransactions
}
