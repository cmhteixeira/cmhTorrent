package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.peerprotocol.{Peer, PeerImpl}
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.cmhtorrent.PieceHash

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.concurrent.ExecutionContext

private[bittorrent] class SwarmImpl private (
    peers: Map[InetSocketAddress, (Peer, SwarmImpl.PeerState)],
    pieces: Map[PieceHash, SwarmImpl.PieceState],
    torrent: Torrent,
    tracker: Tracker,
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    peerConfig: Peer.Config
) extends Swarm {
  tracker.submit(???)
  scheduler.schedule(checkNewPeers, 10, TimeUnit.SECONDS)
  def getPeers = tracker.peers(???)

  private def checkNewPeers: Runnable =
    new Runnable {

      def run(): Unit = {
        val currentPeers = peers
        val trackerPeers = tracker.peers(???).map(_.socketAddress)
        val newPeers = trackerPeers.filterNot(trackerPeers.contains)
        val res = newPeers
          .map(peerSocket =>
            peerSocket ->
              (PeerImpl(
                peerSocket,
                peerConfig,
                torrent.infoHash,
                mainExecutor,
                scheduler,
                torrent.info match {
                  case Torrent.SingleFile(_, _, _, pieces) => pieces.map(_.hex).toList
                  case Torrent.MultiFile(_, _, _, pieces) => pieces.map(_.hex).toList
                }
              ), false)
          )
          .toMap

      }
    }

  private def updatePeerState: Runnable = new Runnable { def run(): Unit = ??? }
}

object SwarmImpl {

  case class Config(downloadDir: Path)

  private sealed trait PieceState extends Product with Serializable
  private case class Requested(peers: NonEmptyList[InetSocketAddress]) extends PieceState
  private case class Downloaded(location: Path) extends PieceState
  private case object Missing extends PieceState

  private sealed trait PeerState extends Product with Serializable
  private case class Off(reason: String) extends PeerState
  private case class On(pieces: Map[String, Boolean]) extends PeerState

  def apply(tracker: Tracker): SwarmImpl =
    new SwarmImpl(Map.empty, Map.empty, ???, tracker, ???, ???, ???)
}
