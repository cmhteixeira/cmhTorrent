package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import scodec.bits.ByteVector

import java.net.SocketAddress
import scala.concurrent.Future

trait Peer {

  def start(): Unit

  def getState: State

  def download(request: BlockRequest): Future[ByteVector]

  def peerAddress: SocketAddress

  def hasPiece(index: Int): Boolean

}

object Peer {
  case class BlockRequest(index: Int, offSet: Int, length: Int)
}


