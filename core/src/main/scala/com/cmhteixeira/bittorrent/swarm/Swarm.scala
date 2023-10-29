package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.bittorrent.consumer
import scodec.bits.BitVector

import scala.concurrent.Future

trait Swarm {
  def getPieces: List[Swarm.PieceState]
  def getPeers: List[Swarm.PeerState]
  def subscribe(s: consumer.Subscriber): Future[Unit]
  def trackerStats: Tracker.Statistics
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
