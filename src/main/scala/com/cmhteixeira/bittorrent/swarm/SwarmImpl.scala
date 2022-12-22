package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import cats.implicits.toTraverseOps
import com.cmhteixeira.bittorrent.peerprotocol.{Peer, PeerFactory}
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.{Active, Downloaded, Missing, PeerState, Pieces}
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.cmhtorrent.PieceHash
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

private[bittorrent] class SwarmImpl private (
    peers: AtomicReference[Map[InetSocketAddress, PeerState]],
    pieces: AtomicReference[Pieces],
    torrent: Torrent,
    tracker: Tracker,
    mainExecutor: ExecutionContext,
    scheduler: ScheduledExecutorService,
    peerFactory: PeerFactory
) extends Swarm {
  private val logger = LoggerFactory.getLogger("Swarm")
  tracker.submit(torrent.toTrackerTorrent)
  scheduler.scheduleAtFixedRate(checkNewPeers, 0, 20, TimeUnit.SECONDS)
  scheduler.scheduleAtFixedRate(updatePeerState, 0, 20, TimeUnit.SECONDS)
  val random = new Random(System.currentTimeMillis()) //todo: Fix this

  private def checkNewPeers: Runnable =
    new Runnable {

      @tailrec
      def run(): Unit = {
        val currentPeers = peers.get()
        val trackerPeers = tracker.peers(torrent.infoHash)
        val newPeerSockets = trackerPeers.filterNot(currentPeers.contains)
        val newPeers = newPeerSockets
          .map(peerSocket => peerSocket -> Active(peerFactory.peer(peerSocket, torrent)))
          .toMap

        if (!peers.compareAndSet(currentPeers, currentPeers ++ newPeers)) run()
        else {
          logger.info(s"Added ${newPeers.size} new peers.")
          newPeers.foreach { case (_, peer) => peer.peer.start() }
        }
      }
    }

  private def afterDownloadFinishes(fut: Future[Path], pieceIndex: Int): Future[Path] =
    fut.andThen {
      case Failure(exception) => logger.warn(s"Failed to download $pieceIndex.", exception)
      case Success(pathOfPiece) => finishedDownloadingPiece(pathOfPiece, pieceIndex)
    }(mainExecutor)

  @tailrec
  private def finishedDownloadingPiece(path: Path, pieceIndex: Int): Unit = {
    val currentState = pieces.get()
    currentState.downloaded(pieceIndex, path) match {
      case Left(Missing) => logger.warn(s"Finished downloading $pieceIndex to '$path', but state is Missing.")
      case Left(Downloaded(thisPath)) =>
        logger.info(s"Finished download piece $pieceIndex to '$path', but piece already downloaded to '$thisPath'.")
      case Left(state) => logger.warn(s"Finished download piece $pieceIndex to '$path', but state is '$state'.")
      case Right(newState) =>
        if (!pieces.compareAndSet(currentState, newState)) finishedDownloadingPiece(path, pieceIndex)
        else logger.info(s"Just download piece $pieceIndex to '$path'.")
    }
  }

  @tailrec
  private def downloadPieceFromPeer(
      pieceIndex: Int,
      peerSocket: InetSocketAddress,
      peer: Peer,
      fut: Future[Path]
  ): Unit = {
    val currentPieces = pieces.get()
    currentPieces.requestedDownload(pieceIndex, peerSocket, fut) match {
      case Left(Downloaded(path)) =>
        logger.info(s"Requested '$peerSocket' to download piece $pieceIndex, but piece already downloaded to '$path'.")
      case Left(state) =>
        logger.info(s"Requested '$peerSocket' to download piece $pieceIndex, but state is '$state'.")
      case Right(newState) =>
        if (!pieces.compareAndSet(currentPieces, newState)) downloadPieceFromPeer(pieceIndex, peerSocket, peer, fut)
        else logger.info(s"Requested '$peerSocket' to download piece $pieceIndex.")
    }
  }

  private def updatePeerState: Runnable =
    new Runnable {

      def run(): Unit =
        Try {
          logger.info("Updating peers states")
          pieces.get().missingPieces.foreach { pieceIndex =>
            val y =
              peers.get().collect { case (socket, Active(peer)) if peer.hasPiece(pieceIndex) => socket -> peer }.toList

            if (y.isEmpty) {
              logger.info(s"No peer has piece $pieceIndex.")
            } else {
              y.lift(random.nextInt(y.size)) match {
                case None => logger.info(s"No peer has piece $pieceIndex.")
                case Some((peerSocket, peer)) =>
                  downloadPieceFromPeer(
                    pieceIndex,
                    peerSocket,
                    peer,
                    afterDownloadFinishes(peer.download(pieceIndex), pieceIndex)
                  )
              }
            }
          }
        } match {
          case Failure(exception) => logger.error("Big error running updatePeerState", exception)
          case Success(_) => logger.info("Completed updatePeerState")
        }
    }

}

object SwarmImpl {

  private case class Pieces(underlying: List[(PieceHash, SwarmImpl.PieceState)]) {

    def downloaded(pieceIndex: Int, path: Path): Either[PieceState, Pieces] =
      underlying.zipWithIndex
        .traverse {
          case ((_, Missing), thisIndex) if thisIndex == pieceIndex => Left(Missing)
          case ((_, d: Downloaded), thisIndex) if thisIndex == pieceIndex => Left(d)
          case ((hash, Requested(_)), thisIndex) if thisIndex == pieceIndex => Right(hash -> Downloaded(path))
          case (pair, _) => Right(pair)
        }
        .map(Pieces(_))

    def requestedDownload(
        pieceIndex: Int,
        peerSocket: InetSocketAddress,
        downloadChannel: Future[Path]
    ): Either[PieceState, Pieces] =
      underlying.zipWithIndex
        .traverse {
          case ((hash, Missing), thisIndex) if thisIndex == pieceIndex =>
            Right(hash -> Requested(NonEmptyList.one(peerSocket -> downloadChannel)))
          case ((_, d: Downloaded), thisIndex) if thisIndex == pieceIndex => Left(d)
          case ((hash, Requested(others)), thisIndex) if thisIndex == pieceIndex =>
            Right(hash -> Requested(others :+ (peerSocket -> downloadChannel)))
          case (pair, _) => Right(pair)
        }
        .map(Pieces(_))

    def missingPieces: List[Int] = underlying.zipWithIndex.collect { case ((_, Missing), index) => index }
  }

  private object Pieces {

    def from(pieces: NonEmptyList[PieceHash]): Pieces = Pieces(pieces.toList.map(hash => hash -> Missing))
  }

  private sealed trait PeerState
  private case class Tried(triedLast: Long) extends PeerState
  private case class Active(peer: Peer) extends PeerState

  private sealed trait PieceState extends Product with Serializable
  private case class Requested(peers: NonEmptyList[(InetSocketAddress, Future[Path])]) extends PieceState
  private case class Downloaded(location: Path) extends PieceState
  private case object Missing extends PieceState

  def apply(
      tracker: Tracker,
      mainExecutor: ExecutionContext,
      scheduler: ScheduledExecutorService,
      peerFactory: PeerFactory,
      torrent: Torrent
  ): SwarmImpl =
    new SwarmImpl(
      new AtomicReference[Map[InetSocketAddress, PeerState]](Map.empty),
      new AtomicReference[Pieces](Pieces.from(torrent.info.pieces)),
      torrent,
      tracker,
      mainExecutor,
      scheduler,
      peerFactory
    )
}
