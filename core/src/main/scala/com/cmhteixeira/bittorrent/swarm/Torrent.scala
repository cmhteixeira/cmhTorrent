package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.{InfoHash, UdpSocket, parseToUdpSocketAddress}
import cats.implicits.{catsSyntaxTuple3Semigroupal, toTraverseOps}
import com.cmhteixeira.bittorrent.swarm.Torrent.{File, split}
import com.cmhteixeira.cmhtorrent
import com.cmhteixeira.cmhtorrent.PieceHash
import com.cmhteixeira.bittorrent.tracker.{Torrent => TrackerTorrent}
import scodec.bits.ByteVector

import scala.annotation.tailrec
import java.nio.file.{Path, Paths}

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

  def fileForBlock(pieceIndex: Int, offset: Int, block: ByteVector): Option[NonEmptyList[Torrent.FileChunk]] =
    info match {
      case sF: Torrent.SingleFile => sF.fileForChunk(pieceIndex, offset, block).map(NonEmptyList.one)
      case mF: Torrent.MultiFile => mF.fileForChunk(pieceIndex, offset, block)
    }
}

object Torrent {

  case class FileChunk(path: Path, offset: Int, block: ByteVector)

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

  case class SingleFile(length: Long, path: Path, pieceLength: Long, pieces: NonEmptyList[PieceHash]) extends Info {

    def fileForChunk(pieceIndex: Int, offset: Int, block: ByteVector): Option[FileChunk] =
      if (pieceLength * pieceIndex + offset + block.length <= length) Some(FileChunk(path, offset, block))
      else None
  }

  object SingleFile {

    def apply(singleFile: com.cmhteixeira.cmhtorrent.SingleFile): Option[SingleFile] =
      NonEmptyList
        .fromList(singleFile.pieces)
        .map(pieces => SingleFile(singleFile.length, Path.of(singleFile.name), singleFile.pieceLength, pieces))
  }

  case class MultiFile(files: NonEmptyList[File], name: Path, pieceLength: Long, pieces: NonEmptyList[PieceHash])
      extends Info {

    @tailrec
    private def findFirst(
        files: List[File],
        remaining: Long,
        block: ByteVector
    ): Option[(FileChunk, List[File], ByteVector)] =
      files match {
        case file :: otherFiles =>
          val chunkSize = file.length - remaining
          if (chunkSize > 0)
            Some(FileChunk(file.path, remaining.toInt, block.take(chunkSize)), otherFiles, block.drop(chunkSize))
          else findFirst(otherFiles, remaining - file.length, block)
        case Nil => None
      }

    private def findNext(files: List[File], block: ByteVector, acc: List[FileChunk]): Option[List[FileChunk]] =
      if (block.isEmpty) Some(acc)
      else
        files match {
          case file :: otherFiles =>
            if (file.length >= block.size) Some(acc :+ FileChunk(file.path, 0, block))
            else findNext(otherFiles, block.drop(file.length), acc :+ FileChunk(file.path, 0, block.take(file.length)))
          case Nil => None
        }

    def fileForChunk(pieceIndex: Int, offset: Int, block: ByteVector): Option[NonEmptyList[FileChunk]] =
      (for {
        (firstFileChunk, remainingFiles, blockLeft) <- findFirst(files.toList, pieceIndex * pieceLength + offset, block)
        remainingFileChunks <- remainingFiles match {
          case Nil => Some(List.empty)
          case remainingFiles => findNext(remainingFiles, blockLeft, List.empty)
        }
      } yield NonEmptyList(firstFileChunk, remainingFileChunks)).map(_.map(fC => fC.copy(path = name.resolve(fC.path))))
  }

  object MultiFile {

    def apply(multiFile: com.cmhteixeira.cmhtorrent.MultiFile): Either[String, MultiFile] =
      for {
        files <- multiFile.files.traverse(File.apply).toRight("Files not correct.")
        files2 <- NonEmptyList.fromList(files).toRight("Files not correct 2")
        pieces <- NonEmptyList.fromList(multiFile.pieces).toRight("Not enough pieces.")
      } yield MultiFile(files2, Path.of(multiFile.name), multiFile.pieceLength, pieces)

  }

  object Info {

    def apply(info: com.cmhteixeira.cmhtorrent.Info): Either[String, Info] =
      info match {
        case s: cmhtorrent.SingleFile => SingleFile(s).toRight("Wrong single file.")
        case m: cmhtorrent.MultiFile => MultiFile(m)
      }
  }

  case class File(length: Long, path: Path)

  object File {

    def apply(file: com.cmhteixeira.cmhtorrent.File): Option[File] =
      NonEmptyList.fromList(file.path).map(path => File(file.length, Paths.get(path.head, path.tail: _*)))
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
