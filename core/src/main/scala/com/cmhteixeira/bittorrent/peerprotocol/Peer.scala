package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import scala.concurrent.Future

trait Peer extends AutoCloseable {
  def download(request: BlockRequest): Future[ByteVector]
  def piece(idx: Int): Unit
  def address: InetSocketAddress
  def subscribe(s: Peer.Subscriber): Future[Unit]
  override def toString: String = address.toString
}

object Peer {

  trait Subscriber {
    def onError(e: Exception): Unit
    def chocked(): Unit

    def unChocked(): Unit

    def hasPiece(idx: Int): Unit
  }
  case class BlockRequest(index: Int, offSet: Int, length: Int) {
    override def toString: String = s"Block[index=$index,offset=$offSet,len=$length]"
  }
}
