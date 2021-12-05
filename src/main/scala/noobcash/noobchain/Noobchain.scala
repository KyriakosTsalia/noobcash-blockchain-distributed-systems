package noobcash.noobchain

import scala.annotation.tailrec

final case class Noobchain(chain: List[Block]) {
  def isValid: Boolean = {
    @tailrec
    def isValidRec(chain: List[Block]): Boolean = chain match {
      case Block(0, _, 0, "1", List(_)) :: Nil => true
      case _ :: Nil => false
      case hd :: tl  if hd.isValid(tl.head) && hd.isNotCorrupted => isValidRec(tl)
      case _ => false
    }
    isValidRec(chain)
  }
  def addBlock(block: Block): Noobchain = Noobchain(block :: chain)
  def addMultipleBlocks(blocks: List[Block]): Noobchain = Noobchain(blocks ++ chain)
  def getLatestBlock: Block = chain.head
  def take(n: Int): List[Block] = chain.take(n)
  def remove(n: Int): Noobchain = {
    @tailrec
    def removeRec(chain: List[Block], n: Int): List[Block] =
      if (n <= 0 || chain.isEmpty) chain
      else removeRec(chain.tail, n - 1)

    Noobchain(removeRec(chain, n))
  }
}
