package noobcash.transaction

import java.security.{MessageDigest, PrivateKey, PublicKey, Signature}

final case class TransactionHttp(senderAddress: String,
                                 receiverAddress: String,
                                 amount: Int,
                                 transactionInputs: List[TransactionInput])

final case class Transaction(senderAddress: PublicKey,
                             receiverAddress: PublicKey,
                             amount: Int,
                             transactionInputs: List[TransactionInput],
                             signature: Option[Seq[Byte]] = None) {

  val transactionId: String = calculateHash()

  def calculateHash(): String =
    MessageDigest.getInstance("SHA-256")
      .digest((senderAddress.toString + receiverAddress + amount + transactionInputs).getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

  def signTransaction(publicKey: PublicKey, privateKey: PrivateKey): Transaction = {
    if(!publicKey.equals(senderAddress)) this // only the sender should sign it
    else {
      val signer = Signature.getInstance("SHA256withRSA")
      signer.initSign(privateKey)
      signer.update(transactionId.getBytes("UTF-8"))
      Transaction(
        senderAddress,
        receiverAddress,
        amount,
        transactionInputs,
        Some(signer.sign()))
    }
  }

  def verifySignature: Boolean = signature match {
    case None => false
    case Some(sig) =>
      val signer = Signature.getInstance("SHA256withRSA")
      signer.initVerify(senderAddress)
      signer.update(transactionId.getBytes("UTF-8"))
      signer.verify(sig.toArray)
  }
}
