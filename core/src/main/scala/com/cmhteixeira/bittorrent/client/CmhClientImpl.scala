package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.{bencode, bittorrent}
import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.swarm.Swarm
import com.cmhteixeira.bittorrent.swarm.{Torrent => SwarmTorrent}

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import com.cmhteixeira.cmhtorrent.Torrent
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress

class CmhClientImpl private (torrents: AtomicReference[Map[InfoHash, Swarm]], swarmFactory: SwarmFactory)
    extends CmhClient {

  private val logger = LoggerFactory.getLogger("cmhTorrent")

  override def downloadTorrent(
      t: SwarmTorrent,
      p: Path
  ): Future[Path] =
    torrents.get().get(t.infoHash) match {
      case Some(_) => Future.failed(new IllegalArgumentException("Torrent already submitted."))
      case None => insertNewTorrent(t.infoHash, swarmFactory.newSwarm(t, p, 16384))
    }

  override def downloadTorrent(
      t: Path,
      p: Path
  ): Either[CmhClient.SubmissionError, Future[Path]] = {
    if (t.toFile.exists()) {
      parseTorrentFromFile(t) match {
        case Left(error) => Left(CmhClient.ParsingError(error))
        case Right(torrent) => Right(downloadTorrent(torrent, p))
      }
    } else Left(CmhClient.FileDoesNotExist)

  }

  private def parseTorrentFromFile(path: Path): Either[String, SwarmTorrent] =
    for {
      a <- bencode.parse(Files.readAllBytes(path)).left.map(_ => "ERROR parsing")
      torrent <- a.as[Torrent].left.map(_.toString)
      swarmTorrent <- SwarmTorrent(InfoHash(a), torrent)
    } yield swarmTorrent

  override def piecesStatus(infoHash: bittorrent.InfoHash): Any = ???

  override def peerStatus(infoHash: bittorrent.InfoHash): Option[Map[InetSocketAddress, Swarm.PeerState]] =
    torrents.get().get(infoHash).map { swarm =>
      swarm.peers
    }

  override def stop(t: bittorrent.InfoHash): Boolean = ???
  override def delete(t: bittorrent.InfoHash): Boolean = ???

  private def insertNewTorrent(infoHash: InfoHash, swarm: Swarm): Future[Path] = {
    val currentState = torrents.get()
    currentState.get(infoHash) match {
      case Some(_) =>
        swarm.close
        Future.failed(new IllegalArgumentException("Torrent already submitted."))
      case None =>
        if (!torrents.compareAndSet(currentState, currentState + (infoHash -> swarm))) insertNewTorrent(infoHash, swarm)
        else Future.successful(Path.of("asd"))
    }
  }
  override def listTorrents: List[InfoHash] = torrents.get().keys.toList

  override def stopAll: Unit = {
    logger.info("Shutting down.")
    torrents.get().foreach { case (_, swarm) => swarm.close }
  }
}

object CmhClientImpl {

  def apply(swarmFactory: SwarmFactory): CmhClientImpl =
    new CmhClientImpl(new AtomicReference[Map[InfoHash, Swarm]](Map.empty), swarmFactory)
}
