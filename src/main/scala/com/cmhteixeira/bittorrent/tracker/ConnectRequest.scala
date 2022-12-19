package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.tracker.ConnectRequest._
import java.nio.ByteBuffer

private[tracker] case class ConnectRequest(transactionId: Int) {

  def serialize: Array[Byte] = {
    val bytes = ByteBuffer.allocate(16)
    bytes.putLong(magicNumber)
    bytes.putInt(action)
    bytes.putInt(transactionId)
    bytes.array()
  }
}

private[tracker] object ConnectRequest {
  private val action: Int = 0
  private val magicNumber = 4497486125440L
}
