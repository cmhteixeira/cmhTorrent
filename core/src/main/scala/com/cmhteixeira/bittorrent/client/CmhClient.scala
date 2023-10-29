package com.cmhteixeira.bittorrent.client

import com.cmhteixeira.bittorrent.{InfoHash, Torrent}
import com.cmhteixeira.bittorrent.tracker.Tracker

import java.nio.file.Path

trait CmhClient extends AutoCloseable {
  def downloadTorrent(t: Torrent, p: Path, s: CmhClient.Subscriber): Either[CmhClient.SubmissionError, Unit]
  def downloadTorrent(t: Path, p: Path, s: CmhClient.Subscriber): Either[CmhClient.SubmissionError, Unit]
  def info(p: Path): Either[String, Torrent]
  def statistics: Map[CmhClient.Torrent, Tracker.Statistics]

  def signal(torrent: String): Unit

  def listTorrents: Map[CmhClient.Torrent, CmhClient.TorrentDetails]

  def stop(t: InfoHash): Boolean
  def delete(t: InfoHash): Boolean

  override def close(): Unit

}

object CmhClient {
  trait Subscriber {
    def onNext(): Unit
    def onComplete(): Unit
    def onError(e: Exception): Unit
  }

  case class Torrent(infoHash: InfoHash, name: String) {
    override def equals(obj: Any): Boolean =
      Option(obj) match {
        case Some(Torrent(thatInfoHash, _)) => thatInfoHash == infoHash
        case _ => false
      }
    override def hashCode(): Int = infoHash.hashCode()
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

  case class Other(msg: String, exception: Throwable) extends SubmissionError
}
