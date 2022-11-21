package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.Serializer
import com.cmhteixeira.bittorrent.tracker.ConnectRequest._

import java.nio.ByteBuffer

sealed trait ConnectRequest {
  def protocolId: MagicNumber.type
  def action: Connect.type
  def transactionId: Int
}

object ConnectRequest {
  case object MagicNumber
  case object Connect

  implicit val deserializer: Serializer[ConnectRequest] = new Serializer[ConnectRequest] {

    override def apply(t: ConnectRequest): Array[Byte] = {
      val bytes = ByteBuffer.allocate(16)
      bytes.putLong(4497486125440L)
      bytes.putInt(0)
      bytes.putInt(t.transactionId)
      bytes.array()
    }
  }

  def apply(transaction: Int): ConnectRequest =
    new ConnectRequest {
      override def protocolId: MagicNumber.type = MagicNumber
      override def action: Connect.type = Connect
      override def transactionId: Int = transaction
    }

}
