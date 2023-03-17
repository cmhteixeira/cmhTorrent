package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.InfoHash
import com.cmhteixeira.bittorrent.swarm.{Swarm, Torrent}
import com.cmhteixeira.bittorrent.tracker.Tracker
import com.cmhteixeira.bittorrent.tracker.Tracker.TrackerState

import java.net.InetSocketAddress
import java.nio.file.Path
import scala.concurrent.Future

trait CmhClient {
  def downloadTorrent(t: Torrent, p: Path): Future[Path]
  def downloadTorrent(t: Path, p: Path): Either[CmhClient.SubmissionError, Future[Path]]
  def piecesStatus(infoHash: InfoHash): Any
  def peerStatus(infoHash: InfoHash): Option[Map[InetSocketAddress, Swarm.PeerState]]

  def statistics: Map[CmhClient.Torrent, Tracker.Statistics]

  def listTorrents: Map[CmhClient.Torrent, CmhClient.TorrentDetails]

  def stop(t: InfoHash): Boolean
  def delete(t: InfoHash): Boolean

  def stopAll: Unit
}

object CmhClient {

  case class Torrent(infoHash: InfoHash, name: String) {

    override def equals(obj: Any): Boolean =
      obj match {
        case Torrent(thatInfoHash, _) => thatInfoHash == infoHash
        case _ => false
      }
  }

  case class TorrentDetails(
      piecesDownloaded: Int,
      piecesTotal: Int,
      peersOn: Int,
      peersConnectedNotActive: Int,
      peersTotal: Int
  )

  sealed trait SubmissionError
  case object FileDoesNotExist extends SubmissionError
  case class ParsingError(someError: String) extends SubmissionError
}
