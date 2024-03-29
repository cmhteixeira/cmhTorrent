package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.tracker.Tracker
trait Swarm extends AutoCloseable {
  def getPieces: List[Swarm.PieceState]
  def getPeers: List[Swarm.PeerState]
  def trackerStats: Tracker.Statistics
  def close(): Unit
}

object Swarm {

  sealed trait PeerState

  object PeerState {
    case class Connected(chocked: Boolean, numPieces: Int) extends PeerState
    case object Unconnected extends PeerState
  }


  sealed trait PieceState

  object PieceState {
    case object Downloaded extends PieceState
    case class Downloading(totalBlocks: Int, completed: Int) extends PieceState
  }
}
