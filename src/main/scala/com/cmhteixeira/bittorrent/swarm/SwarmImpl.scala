package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.peerprotocol.{Peer, PeerImpl}
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.{Missing, Requested}
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.cmhtorrent.PieceHash
import org.slf4j.{LoggerFactory, MDC}

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.concurrent.ExecutionContext

private[bittorrent] class SwarmImpl private (
    peers: AtomicReference[Map[InetSocketAddress, (Peer, SwarmImpl.PeerState)]], //todo: does it need atomic ref?
    pieces: AtomicReference[List[(PieceHash, SwarmImpl.PieceState)]], //todo: does it need atomic ref?
    torrent: Torrent,
    tracker: Tracker,
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    peerConfig: Peer.Config
) extends Swarm {
  private val logger = LoggerFactory.getLogger(getClass)
  MDC.put("<which key should it be>", torrent.infoHash.toString)
  tracker.submit(???)
  scheduler.schedule(checkNewPeers, 10, TimeUnit.SECONDS)
  def getPeers = tracker.peers(???)

  private def checkNewPeers: Runnable =
    new Runnable {

      def run(): Unit = {
        val currentPeers = peers
        val trackerPeers = tracker.peers(???)
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

  private def requestNewPieces: Runnable = new Runnable { def run(): Unit = ??? }

  private def fooBar: Unit = {
    val currentPieces = pieces.get()
    val currentPeers = peers.get()
    currentPieces.zipWithIndex.collectFirst { case ((hash, Missing), index) => (index, hash) } match {
      case Some((indexMissingPiece, pieceHash)) =>
        currentPeers.collectFirst {
          case peerState @ (peerSocket, (peer, SwarmImpl.On(pieces)))
              if pieces.isDefinedAt(indexMissingPiece) && pieces(indexMissingPiece)._2 =>
            peerState

        } match {
          case Some((peerSocket, (peer, _))) =>
            val newState = currentPieces.zipWithIndex.map {
              case ((pieceHash, _), index) if index == indexMissingPiece =>
                (pieceHash, Requested(NonEmptyList.one(peerSocket)))
              case (existing, _) => existing
            }

            if (!pieces.compareAndSet(currentPieces, newState)) fooBar
            else {
              logger.info(s"Asking peer '$peerSocket' to download piece '$indexMissingPiece' - '${pieceHash.hex}'")
              peer.download(indexMissingPiece)
            }

          case None => logger.info(s"No peer contains piece '$indexMissingPiece' (${pieceHash.hex})")
        }
      case None => logger.info("No missing pieces.")
    }
  }
}

object SwarmImpl {

  case class Config(downloadDir: Path)

  private sealed trait PieceState extends Product with Serializable
  private case class Requested(peers: NonEmptyList[InetSocketAddress]) extends PieceState
  private case class Downloaded(location: Path) extends PieceState
  private case object Missing extends PieceState

  private sealed trait PeerState extends Product with Serializable
  private case class Off(reason: String) extends PeerState
  private case class On(pieces: List[(String, Boolean)]) extends PeerState

  def apply(tracker: Tracker): SwarmImpl =
    new SwarmImpl(???, ???, ???, tracker, ???, ???, ???)
}
