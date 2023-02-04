package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.peerprotocol.Peer.BlockRequest
import scodec.bits.{BitVector, ByteVector}

import java.net.SocketAddress
import scala.concurrent.Future

trait Peer {

  def start(): Unit

  def getState: Peer.PeerState

  def download(request: BlockRequest): Future[ByteVector]

  def peerAddress: SocketAddress

  def hasPiece(index: Int): Boolean

  def disconnect(): Unit

}

object Peer {
  case class BlockRequest(index: Int, offSet: Int, length: Int)

  sealed trait PeerState

  case object Begin extends PeerState
  case object TcpConnected extends PeerState
  case class Error(msg: String) extends PeerState
  case class HandShaked(peerId: String, choked: Boolean, interested: Boolean, pieces: BitVector) extends PeerState
}
