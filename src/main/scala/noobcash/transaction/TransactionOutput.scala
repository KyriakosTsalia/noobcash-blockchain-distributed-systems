package noobcash.transaction

import java.security.{MessageDigest, PublicKey}

/**
  * parentTransactionId is the id of its parent transaction
  * recipient is the new owner of the coins
  * amount is the amount of coins
  *
  * @param parentTransactionId: String
  * @param recipient: PublicKey
  * @param amount: Int
  */
final case class TransactionOutput(parentTransactionId: String, recipient: PublicKey, amount: Int) {
  val outputId: String = calculateHash()

  private def calculateHash(): String =
    MessageDigest.getInstance("SHA-256")
      .digest((parentTransactionId + recipient + amount).getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString
}
