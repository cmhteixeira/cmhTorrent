package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.{InfoHash, UdpSocket, parseToUdpSocketAddress}
import cats.implicits.{catsSyntaxTuple3Semigroupal, toTraverseOps}
import com.cmhteixeira.bittorrent.swarm.Torrent.{File, split}
import com.cmhteixeira.cmhtorrent
import com.cmhteixeira.cmhtorrent.PieceHash
import com.cmhteixeira.bittorrent.tracker.{Torrent => TrackerTorrent}

case class Torrent(
    infoHash: InfoHash,
    info: Torrent.Info,
    announce: UdpSocket,
    announceList: Option[NonEmptyList[NonEmptyList[UdpSocket]]]
) {
  def toTrackerTorrent: TrackerTorrent = TrackerTorrent(infoHash, announce, announceList)

  def pieceSize(index: Int): Int = {
    val numberPieces = info.pieces.size
    if (index == numberPieces - 1) {
      (info match {
        case Torrent.SingleFile(length, _, _, _) => length.toInt
        case Torrent.MultiFile(files, _, _, _) =>
          files.foldLeft(0L) { case (accSize, File(size, _)) => accSize + size }.toInt
      }) - (numberPieces - 1) * info.pieceLength.toInt
    } else info.pieceLength.toInt // todo: fix cast
  }

  def splitInBlocks(pieceIndex: Int, blockSize: Int): List[(Int, Int)] = split(pieceSize(pieceIndex), blockSize)
}

object Torrent {

  private def split(pieceSize: Int, blockSize: Int): List[(Int, Int)] = {
    val reminder = pieceSize % blockSize
    if (reminder == 0)
      (0 until (pieceSize / blockSize)).map(i => (i * blockSize, blockSize)).toList
    else {
      val numEquallySizedBlocks = math.floor(pieceSize.toDouble / blockSize.toDouble).toInt
      val allButLastElem = (0 until numEquallySizedBlocks).map(i => (i * blockSize, blockSize)).toList
      val lastElem = (numEquallySizedBlocks * blockSize, reminder)
      allButLastElem :+ lastElem
    }
  }

  sealed trait Info {
    def pieceLength: Long
    def pieces: NonEmptyList[PieceHash]
  }

  case class SingleFile(length: Long, name: String, pieceLength: Long, pieces: NonEmptyList[PieceHash]) extends Info

  object SingleFile {

    def apply(singleFile: com.cmhteixeira.cmhtorrent.SingleFile): Option[SingleFile] =
      NonEmptyList
        .fromList(singleFile.pieces)
        .map(pieces => SingleFile(singleFile.length, singleFile.name, singleFile.pieceLength, pieces))
  }

  case class MultiFile(files: NonEmptyList[File], name: String, pieceLength: Long, pieces: NonEmptyList[PieceHash])
      extends Info

  object MultiFile {

    def apply(multiFile: com.cmhteixeira.cmhtorrent.MultiFile): Either[String, MultiFile] =
      for {
        files <- multiFile.files.traverse(File.apply).toRight("Files not correct.")
        files2 <- NonEmptyList.fromList(files).toRight("Files not correct 2")
        pieces <- NonEmptyList.fromList(multiFile.pieces).toRight("Not enough pieces.")
      } yield MultiFile(files2, multiFile.name, multiFile.pieceLength, pieces)

  }

  object Info {

    def apply(info: com.cmhteixeira.cmhtorrent.Info): Either[String, Info] =
      info match {
        case s: cmhtorrent.SingleFile => SingleFile(s).toRight("Wrong single file.")
        case m: cmhtorrent.MultiFile => MultiFile(m)
      }
  }

  case class File(length: Long, path: NonEmptyList[String])

  object File {

    def apply(file: com.cmhteixeira.cmhtorrent.File): Option[File] =
      NonEmptyList.fromList(file.path).map(path => File(file.length, path))
  }

  //todo: Rethink. There is better way.
  def apply(infoHash: InfoHash, torrent: com.cmhteixeira.cmhtorrent.Torrent): Either[String, Torrent] = {
    val newAnnounceList = torrent.announceList match {
      case Some(announceList) =>
        val t = announceList
          .map { a =>
            val validTrackerURls = a.map(parseToUdpSocketAddress).collect {
              case Right(trackerSocket) => trackerSocket
            }
            NonEmptyList.fromList(validTrackerURls).toRight("Empty list inner.")
          }
          .collect { case Right(tier) => tier }

        NonEmptyList.fromList(t).toRight("Empty list outer").map(a => Option(a))
      case None => Right(None)
    }

    (Info(torrent.info), parseToUdpSocketAddress(torrent.announce), newAnnounceList)
      .mapN { case (info, b, c) => new Torrent(infoHash, info, b, c) }
  }
}