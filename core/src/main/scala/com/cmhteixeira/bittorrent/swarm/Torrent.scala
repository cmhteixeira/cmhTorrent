package com.cmhteixeira.bittorrent.swarm

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.{InfoHash, UdpSocket, parseToUdpSocketAddress}
import cats.implicits.{catsSyntaxTuple3Semigroupal, toTraverseOps}
import com.cmhteixeira.bittorrent.swarm.Torrent.split
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

  def pieceSize(index: Int): Int = info.pieceSize(index)

  def splitInBlocks(pieceIndex: Int, blockSize: Int): List[(Int, Int)] = split(pieceSize(pieceIndex), blockSize)

  def fileChunks(pieceIndex: Int, offset: Int, block: ByteVector): Option[NonEmptyList[Torrent.FileChunk]] =
    info match {
      case sF: Torrent.SingleFile => sF.fileChunk(pieceIndex, offset, block).map(NonEmptyList.one)
      case mF: Torrent.MultiFile => mF.fileChunks(pieceIndex, offset, block)
    }

  def fileSlices(pieceIndex: Int): Option[NonEmptyList[Torrent.FileSlice]] =
    info match {
      case sF: Torrent.SingleFile => sF.fileSlice(pieceIndex).map(NonEmptyList.one)
      case mF: Torrent.MultiFile => mF.fileSlices(pieceIndex)
    }
}

object Torrent {

  case class FileSlice(path: Path, offset: Int, size: Int)
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
    def pieceSize(index: Int): Int
    def pieces: NonEmptyList[PieceHash]
  }

  case class SingleFile(length: Long, path: Path, pieceLength: Long, pieces: NonEmptyList[PieceHash]) extends Info {

    override def pieceSize(index: Int): Int = {
      val numberPieces = pieces.size
      if (index == numberPieces - 1) length.toInt - (numberPieces - 1) * pieceLength.toInt
      else pieceLength.toInt // todo: fix cast
    }

    def fileChunk(pieceIndex: Int, offset: Int, block: ByteVector): Option[FileChunk] =
      if (pieceLength * pieceIndex + offset + block.length <= length) Some(FileChunk(path, offset, block))
      else None

    def fileSlice(pieceIndex: Int): Option[FileSlice] = {
      val startOfPiece = pieceLength * pieceIndex
      val thisPieceLength = pieceSize(pieceIndex)
      if (startOfPiece + thisPieceLength <= length) Some(FileSlice(path, startOfPiece.toInt, thisPieceLength))
      else None
    }
  }

  object SingleFile {

    def apply(singleFile: com.cmhteixeira.cmhtorrent.SingleFile): Option[SingleFile] =
      NonEmptyList
        .fromList(singleFile.pieces)
        .map(pieces => SingleFile(singleFile.length, Path.of(singleFile.name), singleFile.pieceLength, pieces))
  }

  case class MultiFile(files: NonEmptyList[File], name: Path, pieceLength: Long, pieces: NonEmptyList[PieceHash])
      extends Info {

    def fileSlices(index: Int): Option[NonEmptyList[FileSlice]] =
      fileSlices(index * pieceLength.toInt, pieceSize(index))

    def fileChunks(pieceIndex: Int, offset: Int, block: ByteVector): Option[NonEmptyList[FileChunk]] =
      fileSlices(pieceIndex * pieceLength.toInt + offset, block.size.toInt)
        .map(_.foldLeft[(ByteVector, List[FileChunk])]((block, List.empty)) {
          case ((remaininBlock, accu), FileSlice(path, offset, size)) =>
            (remaininBlock.drop(size), accu :+ FileChunk(path, offset, remaininBlock.take(size)))
        })
        .map(_._2)
        .flatMap(NonEmptyList.fromList) // is this safe?

    private def fileSlices(torrentOffset: Int, length: Int): Option[NonEmptyList[FileSlice]] =
      (for {
        (firstFileChunk, remainingFiles, blockLeft) <- findFirst(files.toList, torrentOffset, length)
        remainingFileChunks <- (blockLeft, remainingFiles) match {
          case (0, _) => Some(List.empty)
          case (_, Nil) => None
          case (_, head :: xs) => findNext(NonEmptyList(head, xs), blockLeft)
        }
      } yield NonEmptyList(firstFileChunk, remainingFileChunks)).map(_.map(fC => fC.copy(path = name.resolve(fC.path))))

    @tailrec
    private def findFirst(
        files: List[File],
        remainingOffset: Long,
        length: Long
    ): Option[(FileSlice, List[File], Long)] =
      files match {
        case File(fileLength, path) :: otherFiles =>
          val sliceInThisFile = fileLength - remainingOffset
          if (sliceInThisFile >= length) Some(FileSlice(path, remainingOffset.toInt, length.toInt), otherFiles, 0)
          else if (sliceInThisFile > 0)
            Some(FileSlice(path, remainingOffset.toInt, sliceInThisFile.toInt), otherFiles, length - sliceInThisFile)
          else findFirst(otherFiles, remainingOffset - fileLength, length)
        case Nil => None
      }

    private def findNext(files: NonEmptyList[File], remaining: Long): Option[List[FileSlice]] = {
      @tailrec
      def internal(files: List[File], remaining: Long, acc: List[FileSlice]): Option[List[FileSlice]] =
        if (remaining == 0) Some(acc)
        else
          files match {
            case File(fileLength, path) :: otherFiles =>
              if (fileLength >= remaining) Some(acc :+ FileSlice(path, 0, remaining.toInt))
              else internal(otherFiles, remaining - fileLength, acc :+ FileSlice(path, 0, fileLength.toInt))
            case Nil => None
          }
      internal(files.toList, remaining, List.empty)
    }

    override def pieceSize(index: Int): Int = {
      val numberPieces = pieces.size
      if (index == numberPieces - 1) {
        files
          .foldLeft(0L) { case (accSize, File(size, _)) => accSize + size }
          .toInt - (numberPieces - 1) * pieceLength.toInt
      } else pieceLength.toInt // todo: fix cast
    }
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
