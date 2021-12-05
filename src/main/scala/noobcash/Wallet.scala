package noobcash

import java.security.{KeyPair, KeyPairGenerator}

/**
  * Just a simple publicKey-privateKey pair generator
  */
case object Wallet {
  def apply(): KeyPair = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    kpg.generateKeyPair()
  }
}