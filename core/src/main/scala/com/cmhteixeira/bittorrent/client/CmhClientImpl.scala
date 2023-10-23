package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.{bencode, bittorrent}
import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.swarm.Swarm.PieceState
import com.cmhteixeira.bittorrent.swarm.{Swarm, Torrent => SwarmTorrent}
import com.cmhteixeira.bittorrent.tracker.Tracker
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import com.cmhteixeira.cmhtorrent.Torrent
import org.slf4j.LoggerFactory

class CmhClientImpl private (
    torrents: AtomicReference[Map[CmhClient.Torrent, Swarm]],
    swarmFactory: SwarmFactory
) extends CmhClient {

  private val logger = LoggerFactory.getLogger("cmhTorrent")

  override def downloadTorrent(
      t: SwarmTorrent,
      p: Path
  ): Future[Path] =
    torrents.get().find { case (CmhClient.Torrent(infoHash, _), _) => infoHash == t.infoHash } match {
      case Some(_) => Future.failed(new IllegalArgumentException("Torrent already submitted."))
      case None =>
        insertNewTorrent(CmhClient.Torrent(t.infoHash, t.info.torrentName), swarmFactory.newSwarm(t, p, 16384))
    }

  override def downloadTorrent(
      t: Path,
      p: Path
  ): Either[CmhClient.SubmissionError, Future[Path]] = {
    val res = if (t.toFile.exists()) {
      parseTorrentFromFile(t) match {
        case Left(error) => Left(CmhClient.ParsingError(error))
        case Right(torrent) => Right(downloadTorrent(torrent, p))
      }
    } else Left(CmhClient.FileDoesNotExist)
    res
  }

  private def parseTorrentFromFile(path: Path): Either[String, SwarmTorrent] =
    for {
      a <- bencode.parse(Files.readAllBytes(path)).left.map(_ => "ERROR parsing")
      info <- a.asDict.flatMap(_.apply("info")).toRight("Could not extract valid 'info' from Bencode.")
      torrent <- a.as[Torrent].left.map(_.toString)
      swarmTorrent <- SwarmTorrent(InfoHash(info), torrent)
    } yield swarmTorrent

  override def stop(t: bittorrent.InfoHash): Boolean = ???
  override def delete(t: bittorrent.InfoHash): Boolean = ???

  private def insertNewTorrent(torrent: CmhClient.Torrent, swarm: Swarm): Future[Path] = {
    val currentState = torrents.get()
    currentState.find { case (CmhClient.Torrent(thisInfohash, _), _) => thisInfohash == torrent.infoHash } match {
      case Some(_) =>
        swarm.close
        Future.failed(new IllegalArgumentException("Torrent already submitted."))
      case None =>
        if (!torrents.compareAndSet(currentState, currentState + (torrent -> swarm))) insertNewTorrent(torrent, swarm)
        else Future.successful(Path.of("asd"))
    }
  }

  override def listTorrents: Map[CmhClient.Torrent, CmhClient.TorrentDetails] =
    torrents
      .get()
      .map { case (torrent: CmhClient.Torrent, swarm) =>
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
    torrents.get().foreach { case (_, swarm) => swarm.close() }
  }

  override def statistics: Map[CmhClient.Torrent, Tracker.Statistics] =
    torrents.get().map { case (clientTorrent, swarm) => clientTorrent -> swarm.trackerStats }
  override def info(p: Path): Either[String, SwarmTorrent] = parseTorrentFromFile(p)
}

object CmhClientImpl {

  def apply(swarmFactory: SwarmFactory): CmhClientImpl =
    new CmhClientImpl(new AtomicReference[Map[CmhClient.Torrent, Swarm]](Map.empty), swarmFactory)
}
