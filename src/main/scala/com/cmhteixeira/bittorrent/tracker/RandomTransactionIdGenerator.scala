package com.cmhteixeira.bittorrent.tracker

import java.nio.ByteBuffer
import java.security.SecureRandom

class RandomTransactionIdGenerator private (s: SecureRandom) extends TransactionIdGenerator {

  override def newTransactionId(): Int = {
    val byteBuffer = ByteBuffer.allocate(4)
    s.nextBytes(byteBuffer.array())
    byteBuffer.getInt
  }
}

object RandomTransactionIdGenerator {
  def apply(s: SecureRandom): RandomTransactionIdGenerator = new RandomTransactionIdGenerator(s)
}
