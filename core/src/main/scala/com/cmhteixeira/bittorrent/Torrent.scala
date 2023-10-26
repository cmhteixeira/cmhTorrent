package com.cmhteixeira.bittorrent

import cats.data.NonEmptyList
import cats.implicits.{catsSyntaxTuple3Semigroupal, toTraverseOps}
import com.cmhteixeira.bittorrent.Torrent.split
import com.cmhteixeira.bittorrent.UdpSocket.parseToUdpSocketAddress
import scodec.bits.ByteVector
import cats.Show
import cats.implicits.{
  catsStdInstancesForEither,
  catsSyntaxTuple2Semigroupal,
  catsSyntaxTuple3Semigroupal,
  toTraverseOps
}
import com.cmhteixeira.bencode
import com.cmhteixeira.bencode.Bencode.{BByteString, BDictionary, BInteger, BList}
import com.cmhteixeira.bencode.DecodingFailure.{DictionaryKeyMissing, DifferentTypeExpected, GenericDecodingFailure}
import com.cmhteixeira.bencode.{Bencode, Decoder, DecodingFailure, Encoder}
import org.apache.commons.codec.binary.Hex

import java.nio.file.{Path, Paths}
import java.sql.Date
import java.util
import scala.annotation.tailrec

case class Torrent(
    infoHash: InfoHash,
    info: Torrent.Info,
    announce: UdpSocket,
    announceList: Option[NonEmptyList[NonEmptyList[UdpSocket]]],
    creationDate: Option[Long],
    comment: Option[String],
    createdBy: Option[String]
) {
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

  /** Helper case class used to decode an array[byte] into.
    *
    * Extremely similar to the source of truth [[Torrent]]
    */
  private case class TorrentDecoderHelper(
      info: TorrentDecoderHelper.Info,
      announce: String,
      announceList: Option[List[List[String]]],
      creationDate: Option[Long],
      comment: Option[String],
      createdBy: Option[String]
  )

  private object TorrentDecoderHelper {
    private val announceField = "announce"
    private val infoField = "info"
    private val announceListField = "announce-list"
    private val commentField = "comment"
    private val createdByField = "created by"
    private val creationDateField = "creation date"

    sealed trait Info

    object Info {
      private val lengthField = "length"
      private val filesField = "files"
      private val nameField = "name"
      private val pieceLengthField = "piece length"
      private val piecesField = "pieces"

      implicit val decoder: Decoder[Info] = new Decoder[Info] {

        private def commonFields(dict: BDictionary): Either[DecodingFailure, (String, Long, List[PieceHash])] = {
          (
            for {
              value <- dict(nameField).toRight(DictionaryKeyMissing(nameField))
              str <- value.asString.toRight(GenericDecodingFailure("asd"))
            } yield str,
            for {
              value <- dict(pieceLengthField).toRight(DictionaryKeyMissing(pieceLengthField))
              benInt <- value.asNumber.toRight(GenericDecodingFailure("erer")).map(_.underlying)
            } yield benInt, {
              for {
                value <- dict(piecesField).toRight(DictionaryKeyMissing(piecesField))
                str <- value match {
                  case BByteString(underlying) => Right(underlying)
                  case a => Left(DifferentTypeExpected("BByteString", a.getClass.getName))
                }
                hexHashes <-
                  if (str.length % 20 != 0)
                    Left(GenericDecodingFailure("Pieces field does not contain multiple of 20 bytes."))
                  else
                    (0 until (str.length / 20))
                      .map(i => (i * 20, i * 20 + 20))
                      .map { case (startInclusive, endExclusive) =>
                        util.Arrays.copyOfRange(str, startInclusive, endExclusive)
                      }
                      .toList
                      .traverse(PieceHash(_))
                      .toRight(GenericDecodingFailure("Error extracting one or more piece hashes."))

              } yield hexHashes
            }
          ).mapN { case (name, pieceLength, pieces) => (name, pieceLength, pieces) }
        }

        override def apply(
            t: Bencode
        ): Either[DecodingFailure, Info] =
          t match {
            case dict: Bencode.BDictionary =>
              (dict(lengthField), dict(filesField)) match {
                case (None, None) => Left(DictionaryKeyMissing(lengthField))
                case (Some(single), Some(multiple)) =>
                  Left(
                    GenericDecodingFailure(
                      s"Both fields '$lengthField' and '$filesField' present when only one should exist."
                    )
                  )
                case (Some(BInteger(length)), None) =>
                  commonFields(dict).map { case (name, pieceLen, pieces) => // todo: There is validation I can do here!
                    SingleFile(length, name, pieceLen, pieces)
                  }
                case (Some(a), None) => Left(DifferentTypeExpected("BInteger", a.getClass.toString))
                case (None, Some(bEncode)) =>
                  (bEncode.as[List[File]], commonFields(dict)).mapN { case (files, (a, b, c)) =>
                    MultiFile(files, a, b, c)
                  }
              }
            case _ => Left(DecodingFailure.NotABdictionary)
          }
      }

      implicit val encoder: Encoder[Info] = new Encoder[Info] {

        private def commonFields(name: String, pieceLength: Long, pieces: List[PieceHash]): BDictionary =
          Bencode.dictStringKeys(
            (nameField, Bencode.fromString(name)),
            (pieceLengthField, Bencode.fromLong(pieceLength)),
            (piecesField, BByteString(pieces.map(_.bytes).foldLeft(Array.empty[Byte]) { _ ++ _ }))
          )

        override def apply(t: Info): Bencode =
          t match {
            case SingleFile(length, name, pieceLength, pieceHashes) =>
              BDictionary(Map(Bencode.fromString(lengthField) -> Bencode.fromLong(length)))
                .merge(commonFields(name, pieceLength, pieceHashes))
            case MultiFile(files, name, pieceLength, pieceHashes) =>
              Bencode
                .dictStringKeys((filesField, BList(files.map(file => File.encoder(file)))))
                .merge(commonFields(name, pieceLength, pieceHashes))
          }
      }
    }

    case class SingleFile(length: Long, name: String, pieceLength: Long, pieces: List[PieceHash]) extends Info

    case class MultiFile(files: List[File], name: String, pieceLength: Long, pieces: List[PieceHash]) extends Info

    case class File(length: Long, path: List[String])

    object File {
      private val lengthField = "length"
      private val pathField = "path"

      implicit val decoder: Decoder[File] = new Decoder[File] {

        override def apply(
            t: Bencode
        ): Either[DecodingFailure, File] =
          t match {
            case dict: Bencode.BDictionary =>
              val length = for {
                value <- dict(lengthField).toRight(DictionaryKeyMissing(lengthField))
                str <- value.asNumber.toRight(GenericDecodingFailure("notString"))
              } yield str.underlying

              val path = for {
                value <- dict(pathField).toRight(DictionaryKeyMissing(pathField))
                list <- value.asList.toRight(GenericDecodingFailure("NotList"))
                listStr <- list.traverse(_.asString.toRight(GenericDecodingFailure("asdsa")))
              } yield listStr

              (length, path).mapN { case (l, p) => File(l, p) }

            case _ => Left(DecodingFailure.NotABdictionary)
          }
      }

      implicit val encoder: Encoder[File] = new Encoder[File] {

        override def apply(t: File): Bencode =
          BDictionary(
            Map(
              Bencode.fromString(lengthField) -> Bencode.fromLong(t.length),
              Bencode.fromString(pathField) -> BList(t.path.map(Bencode.fromString))
            )
          )
      }
    }

    implicit val decoder: Decoder[TorrentDecoderHelper] = new Decoder[TorrentDecoderHelper] {

      override def apply(
          t: Bencode
      ): Either[DecodingFailure, TorrentDecoderHelper] = {

        t match {
          case dict: Bencode.BDictionary =>
            val trackerUrl =
              for {
                value <- dict(announceField).toRight(DictionaryKeyMissing(announceField))
                str <- value.asString.toRight(GenericDecodingFailure("asd"))
              } yield str

            val info = dict(infoField).map(_.as[Info]).getOrElse(Left(DictionaryKeyMissing(infoField)))

            val announceList =
              for {
                value <- dict(announceListField)
                asListOfLists <- value.as[List[List[Bencode]]].toOption
                asListOfListOfStrings <- asListOfLists.traverse(_.traverse(_.asString))
              } yield asListOfListOfStrings

            val comment = dict(commentField).flatMap(_.asString)

            val createdBy = dict(createdByField).flatMap(_.asString)

            val creationDate = dict(creationDateField).flatMap(_.asLong)

            (trackerUrl, info).mapN { case (announce, info) =>
              TorrentDecoderHelper(info, announce, announceList, creationDate, comment, createdBy)
            }
          case _ => Left(DecodingFailure.NotABdictionary)
        }
      }
    }

    implicit val encoder: Encoder[TorrentDecoderHelper] = new Encoder[TorrentDecoderHelper] {

      override def apply(t: TorrentDecoderHelper): Bencode = {
        val TorrentDecoderHelper(info, announce, announceList, creationDate, comment, createdBy) = t

        val mainFields = BDictionary(
          Map(
            Bencode.fromString(announceField) -> Bencode.fromString(announce),
            Bencode.fromString(infoField) -> Info.encoder(info)
          )
        )

        val announceDict =
          announceList.map(j => BList(j.map(i => BList(i.map(Bencode.fromString)))))

        val commentDict =
          comment.map(a => BDictionary(Map(Bencode.fromString(commentField) -> Bencode.fromString(a))))

        val createdByDict =
          createdBy.map(a => BDictionary(Map(Bencode.fromString(createdByField) -> Bencode.fromString(a))))

        val creationDateDict =
          creationDate.map(a => BDictionary(Map(Bencode.fromString(creationDateField) -> Bencode.fromLong(a))))

        List(announceDict, commentDict, createdByDict, creationDateDict).flatten
          .foldLeft[Bencode](mainFields) { case (a, b) => a merge b }
      }
    }
  }

  sealed trait PieceHash {
    def hex: String
    def bytes: Array[Byte]

    override def equals(obj: Any): Boolean =
      if (!obj.isInstanceOf[PieceHash]) false else util.Arrays.equals(bytes, obj.asInstanceOf[PieceHash].bytes)

    override def toString: String = hex
  }

  object PieceHash {

    def apply(in: Array[Byte]): Option[PieceHash] =
      if (in.length != 20) None
      else
        Some(new PieceHash {
          override def hex: String = Hex.encodeHexString(in)
          override def bytes: Array[Byte] = util.Arrays.copyOf(in, in.length)
        })
  }

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
    def torrentName: String
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

    override def torrentName: String = path.toString
  }

  object SingleFile {

    def apply(singleFile: TorrentDecoderHelper.SingleFile): Option[SingleFile] =
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
    override def torrentName: String = name.toString
  }

  object MultiFile {

    def apply(multiFile: TorrentDecoderHelper.MultiFile): Either[String, MultiFile] =
      for {
        files <- multiFile.files.traverse(File.apply).toRight("Files not correct.")
        files2 <- NonEmptyList.fromList(files).toRight("Files not correct 2")
        pieces <- NonEmptyList.fromList(multiFile.pieces).toRight("Not enough pieces.")
      } yield MultiFile(files2, Path.of(multiFile.name), multiFile.pieceLength, pieces)

  }

  object Info {

    def apply(info: TorrentDecoderHelper.Info): Either[String, Info] =
      info match {
        case s: TorrentDecoderHelper.SingleFile => SingleFile(s).toRight("Wrong single file.")
        case m: TorrentDecoderHelper.MultiFile => MultiFile(m)
      }
  }

  case class File(length: Long, path: Path)

  object File {

    def apply(file: TorrentDecoderHelper.File): Option[File] =
      NonEmptyList.fromList(file.path).map(path => File(file.length, Paths.get(path.head, path.tail: _*)))

  }

  implicit val show: Show[Torrent] = new Show[Torrent] {

    def showSingle(t: SingleFile, tab: String): String =
      s"""Single-File
         |  name: ${t.torrentName}
         |  length: ${t.length}
         |  pieceLength: ${t.pieceLength}
         |  pieces: ${t.pieces.take(15)} [truncated]""".stripMargin

    def showMulti(t: MultiFile, tab: String): String =
      s"""Multi-File
         |  dirName: ${t.name}
         |  files: ${t.files
          .map { case File(size, dirStructure) =>
            s"""
               |${tab}File: ${dirStructure.toString}
               |${tab}Size: $size""".stripMargin
          }
          .toList
          .mkString("")}
         |  pieceLength: ${t.pieceLength}
         |  pieces:
         |    [${t.pieces.take(20).mkString(", ")}]${if (t.pieces.size <= 20) ""
        else s"[showing ${20}/${t.pieces.size}]"}""".stripMargin

    override def show(t: Torrent): String = {

      val rest = t.info match {
        case single: SingleFile => showSingle(single, "")
        case multi: MultiFile => showMulti(multi, "    ")
      }

      s"""
         |Torrent
         |announce: ${t.announce}
         |info
         |$rest
         |announceList:\n    ${t.announceList
          .fold("")(p =>
            "[" + p.map(i => "[" + i.map(_.toString).toList.mkString(", ") + "]").toList.mkString(", ") + "]"
          )}
         |creationDate: ${t.creationDate.map(a => new Date(a * 1000).toLocalDate.toString).getOrElse("NA")}
         |comment: ${t.comment.getOrElse("NA")}
         |createdBy: ${t.createdBy.getOrElse("NA")}
         |""".stripMargin
    }
  }

  def apply(rawData: Array[Byte]): Either[String, Torrent] = {
    for {
      a <- bencode.parse(rawData).left.map(_ => "ERROR parsing")
      info <- a.asDict.flatMap(_.apply("info")).toRight("Could not extract valid 'info' from Bencode.")
      torrent <- a.as[TorrentDecoderHelper].left.map(_.toString)
      swarmTorrent <- Torrent(InfoHash(info), torrent)
    } yield swarmTorrent
  }
  def apply(infoHash: InfoHash, torrent: TorrentDecoderHelper): Either[String, Torrent] = {
    val newAnnounceList = torrent.announceList match {
      case Some(announceList) =>
        val t = announceList
          .map { a =>
            val validTrackerURls = a.map(parseToUdpSocketAddress).collect { case Right(trackerSocket) =>
              trackerSocket
            }
            NonEmptyList.fromList(validTrackerURls).toRight("Empty list inner.")
          }
          .collect { case Right(tier) => tier }

        NonEmptyList.fromList(t).toRight("Empty list outer").map(a => Option(a))
      case None => Right(None)
    }

    (Info(torrent.info), parseToUdpSocketAddress(torrent.announce), newAnnounceList)
      .mapN { case (info, b, c) =>
        new Torrent(infoHash, info, b, c, torrent.creationDate, torrent.comment, torrent.createdBy)
      }
  }
}
