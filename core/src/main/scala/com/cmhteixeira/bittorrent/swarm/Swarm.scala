package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.swarm.Swarm.PeerState

import java.net.InetSocketAddress
import java.nio.file.Path
import scala.concurrent.Future

trait Swarm {
  def getPeers: Map[InetSocketAddress, PeerState]
  def getPieces: List[Swarm.PieceState]

  def downloadCompleted: Future[Path]

  def close: Unit
}

object Swarm {
  sealed trait PieceState
  case class Downloaded(location: Path) extends PieceState
  case object Missing extends PieceState

  case class Downloading(
      totalBlocks: Int,
      completed: Int
  ) extends PieceState

  sealed trait PeerState
  case class Tried(las: Long) extends PeerState
  case class On(peerState: Peer.PeerState) extends PeerState

}
