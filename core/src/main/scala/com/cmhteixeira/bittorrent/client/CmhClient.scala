package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.{InfoHash, Torrent}
import com.cmhteixeira.bittorrent.tracker.Tracker

import java.nio.file.Path
import scala.concurrent.Future

trait CmhClient extends AutoCloseable {
  def downloadTorrent(t: Torrent, p: Path): Future[Path]
  def downloadTorrent(t: Path, p: Path): Either[CmhClient.SubmissionError, Future[Path]]
  def info(p: Path): Either[String, Torrent]
  def statistics: Map[CmhClient.Torrent, Tracker.Statistics]

  def listTorrents: Map[CmhClient.Torrent, CmhClient.TorrentDetails]

  def stop(t: InfoHash): Boolean
  def delete(t: InfoHash): Boolean

  override def close(): Unit

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
      peersTotal: Int,
      peersConnected: Int,
      peersUnChocked: Int,
      peersUnChokedWithPieces: Int
  )

  sealed trait SubmissionError
  case object FileDoesNotExist extends SubmissionError
  case class ParsingError(someError: String) extends SubmissionError
}
