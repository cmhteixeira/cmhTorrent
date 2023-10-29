package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent
import com.cmhteixeira.bittorrent.client.CmhClientImpl.SwarmState
import com.cmhteixeira.bittorrent.client.history.Monkey
import com.cmhteixeira.bittorrent.{InfoHash, Torrent}
import com.cmhteixeira.bittorrent.consumer.{FileSystemSink, Subscriber, Subscription}
import com.cmhteixeira.bittorrent.peerprotocol.Peer
import com.cmhteixeira.bittorrent.swarm.Swarm.PieceState
import com.cmhteixeira.bittorrent.swarm.Swarm
import com.cmhteixeira.bittorrent.tracker.Tracker

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future}
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import java.io.InputStream
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

class CmhClientImpl private (
    fsSink: FileSystemSink,
    torrents: AtomicReference[CmhClientImpl.State],
    swarmFactory: SwarmFactory
) extends CmhClient {

  private val logger = LoggerFactory.getLogger("cmhTorrent")

  def signal(torrent: String): Unit = {
    val currentState = torrents.get()
    currentState.find(torrent) match {
      case Some((_, SwarmState.Normal)) => logger.info(s"TODO-Downstream signal $torrent. Not debug.")
      case Some((_, debug @ SwarmState.Debug(dowstream, true, subscription))) => ???
      case Some((_, debug @ SwarmState.Debug(dowstream, false, subscription))) =>
        if (dowstream > 0) {
          val newState = ???
          if (!torrents.compareAndSet(currentState, newState)) signal(torrent)
          else subscription.request(1)
        } else {
          val newState = ???
          if (!torrents.compareAndSet(currentState, newState)) signal(torrent)
          else ()
        }
      case None => logger.warn(s"TODO-Downstream signal $torrent by $i")
    }
  }

  private def downstreamSignal(torrent: InfoHash, i: Int): Unit = {
    val currentState = torrents.get()
    currentState.find(torrent) match {
      case Some((_, SwarmState.Normal)) => logger.info(s"TODO-Downstream signal $torrent by $i. Not debug.")
      case Some((_, debug @ SwarmState.Debug(dowstream, allowDemand, subscription))) =>
        if (allowDemand) {
          val newState = ???
          if (!torrents.compareAndSet(currentState, newState)) downstreamSignal(torrent, i)
          else subscription.request(1)
        } else {
          val newState = ???
          if (!torrents.compareAndSet(currentState, newState)) downstreamSignal(torrent, i)
          else ()
        }
      case None => logger.warn(s"TODO-Downstream signal $torrent by $i")
    }
  }

  private class FlowControlSubscription(torrent: InfoHash, real: Subscription) extends Subscription {
    override def cancel(): Unit = real.cancel()
    override def request(i: Int): Unit = downstreamSignal(torrent, i)
    override def completed(pieceIdx: Int): Unit = {
      real.completed(pieceIdx)
    }
    override def wrongHash(pieceIdx: Int): Unit = real.wrongHash(pieceIdx)
  }

  private class SpySubscriber(torrent: InfoHash, funil: Subscriber) extends Subscriber {
    override def onSubscribe(s: Subscription): Unit = funil.onSubscribe(new FlowControlSubscription(torrent, s))
    override def onNext(idx: Int, offset: Int, data: ByteVector): Future[Unit] = funil.onNext(idx, offset, data)
    override def onComplete(): Unit = funil.onComplete()
    override def read(bR: Peer.BlockRequest): Future[ByteVector] =
      funil.read(bR)
  }

  override def downloadTorrent(
      torrent: Torrent,
      p: Path,
      s: CmhClient.Subscriber
  ): Either[CmhClient.SubmissionError, Unit] = {
    torrents.get().find(torrent.infoHash) match {
      case Some(_) =>
        logger.info(s"Torrent $torrent already exists.")
        Right(())
      case None =>
        val swarm = swarmFactory.newSwarm(torrent, 16384)
        val subscriber = fsSink.createSubscriber(torrent, p)
        insertNewTorrent(CmhClient.Torrent(torrent.infoHash, torrent.info.torrentName), swarm) match {
          case Failure(exception) => Left(CmhClient.Other("asd", exception))
          case Success(_) =>
            Try(Await.result(swarm.subscribe(subscriber), 1.second)) match {
              case Failure(exception) => Left(CmhClient.Other("ASDASD", exception))
              case Success(_) => Right(())
            }
        }
    }
  }

  override def downloadTorrent(
      t: Path,
      p: Path,
      s: CmhClient.Subscriber
  ): Either[CmhClient.SubmissionError, Unit] = {
    val res = if (t.toFile.exists()) {
      parseTorrentFromFile(t) match {
        case Left(error) => Left(CmhClient.ParsingError(error))
        case Right(torrent) => downloadTorrent(torrent, p, s)
      }
    } else Left(CmhClient.FileDoesNotExist)
    res
  }

  private def parseTorrentFromFile(path: Path): Either[String, Torrent] = Torrent(Files.readAllBytes(path))

  override def stop(t: bittorrent.InfoHash): Boolean = ???
  override def delete(t: bittorrent.InfoHash): Boolean = ???

  private def insertNewTorrent(torrent: CmhClient.Torrent, swarm: Swarm): Try[Unit] = {
    val currentState = torrents.get()
    currentState.find { case (CmhClient.Torrent(thisInfohash, _), _) => thisInfohash == torrent.infoHash } match {
      case Some(_) =>
        Failure(new IllegalArgumentException("Torrent already submitted."))
      case None =>
        if (!torrents.compareAndSet(currentState, currentState + (torrent -> swarm))) insertNewTorrent(torrent, swarm)
        else Success(())
    }
  }

  override def listTorrents: Map[CmhClient.Torrent, CmhClient.TorrentDetails] =
    torrents
      .get()
      .underlyng
      .map { case (torrent: CmhClient.Torrent, (swarm, _)) =>
        val pieces = swarm.getPieces
        val piecesDownloaded = pieces.count {
          case PieceState.Downloaded => true
          case _ => false
        }
        val peersInfo = swarm.getPeers
        val totalPeers = peersInfo.size
        val peersConnected = peersInfo.collect { case connected: Swarm.PeerState.Connected => connected }
        val peersUnchoked = peersConnected.count { case Swarm.PeerState.Connected(chocked, _) => !chocked }
        val peersUnchokedWithPieces = peersConnected.count { case Swarm.PeerState.Connected(chocked, numPieces) =>
          !chocked && numPieces > 0
        }

        torrent ->
          CmhClient
            .TorrentDetails(
              piecesDownloaded,
              pieces.size,
              totalPeers,
              peersConnected.size,
              peersUnchokedWithPieces,
              peersUnchoked
            )
      }

  override def close(): Unit = {
    logger.info("Shutting down.")
    fsSink.shutdown()
  }

  override def statistics: Map[CmhClient.Torrent, Tracker.Statistics] =
    torrents.get().underlyng.map { case (clientTorrent, (swarm, _)) => clientTorrent -> swarm.trackerStats }
  override def info(p: Path): Either[String, Torrent] = parseTorrentFromFile(p)

}

object CmhClientImpl {

  private case class State(underlyng: Map[CmhClient.Torrent, (Swarm, SwarmState)]) {
    def find(infoHash: InfoHash): Option[(Swarm, SwarmState)] = underlyng.get(CmhClient.Torrent(infoHash, "irrelevant"))

    def find(name: String): Option[(Swarm, SwarmState)] = underlyng
      .find { case (torrent, tuple) =>
        torrent.name == name
      }
      .map(_._2)
  }

  private sealed trait SwarmState
  private object SwarmState {
    object Normal extends SwarmState
    case class Debug(downstreamDemand: Int, allowDemand: Boolean, subscription: Subscription) extends SwarmState
  }

  def apply(swarmFactory: SwarmFactory, executionContext: ExecutionContext): CmhClientImpl =
    new CmhClientImpl(
      FileSystemSink(executionContext),
      new AtomicReference[Map[CmhClient.Torrent, Swarm]](Map.empty),
      swarmFactory
    )
}
