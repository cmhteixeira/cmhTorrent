package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.swarm.State.PieceState
import com.cmhteixeira.bittorrent.swarm.Swarm.PeerState

import java.net.InetSocketAddress

trait Swarm {
  def peers: Map[InetSocketAddress, PeerState]
  def pieces: List[PieceState]

  def close: Unit
}

object Swarm {
  sealed trait PeerState
  case class Tried(las: Long) extends PeerState
  case class On(peerState: Peer.PeerState) extends PeerState

}
