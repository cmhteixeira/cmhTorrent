package com.cmhteixeira.cmhtorrent

import cats.Show
import cats.implicits.{
  catsStdInstancesForEither,
  catsSyntaxTuple2Semigroupal,
  catsSyntaxTuple3Semigroupal,
  toTraverseOps
}
import com.cmhteixeira.bencode.Bencode.{BByteString, BDictionary, BInteger, BList}
import com.cmhteixeira.bencode.DecodingFailure.{DictionaryKeyMissing, DifferentTypeExpected, GenericDecodingFailure}
import com.cmhteixeira.bencode.{Bencode, Decoder, DecodingFailure, Encoder}
import org.apache.commons.codec.binary.Hex

import java.sql.Date
import java.util

case class Torrent(
    info: Info,
    announce: String,
    announceList: Option[List[List[String]]],
    creationDate: Option[Long],
    comment: Option[String],
    createdBy: Option[String]
)

sealed trait Info

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

object Torrent {
  private val announceField = "announce"
  private val infoField = "info"
  private val announceListField = "announce-list"
  private val commentField = "comment"
  private val createdByField = "created by"
  private val creationDateField = "creation date"

  implicit val decoder: Decoder[Torrent] = new Decoder[Torrent] {

    override def apply(
        t: Bencode
    ): Either[DecodingFailure, Torrent] = {

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

          (trackerUrl, info).mapN {
            case (announce, info) => Torrent(info, announce, announceList, creationDate, comment, createdBy)
          }
        case _ => Left(DecodingFailure.NotABdictionary)
      }
    }
  }

  implicit val encoder: Encoder[Torrent] = new Encoder[Torrent] {

    override def apply(t: Torrent): Bencode = {
      val Torrent(info, announce, announceList, creationDate, comment, createdBy) = t

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

  implicit val show: Show[Torrent] = new Show[Torrent] {

    def showSingle(t: SingleFile, tab: String): String =
      s"""Single-File
         |  name: ${t.name}
         |  length: ${t.length}
         |  pieceLength: ${t.pieceLength}
         |  pieces: ${t.pieces.take(15)} [truncated]""".stripMargin

    def showMulti(t: MultiFile, tab: String): String =
      s"""Multi-File
         |  dirName: ${t.name}
         |  files: ${t.files
        .map {
          case File(size, dirStructure) =>
            s"""
            |${tab}File: ${dirStructure.mkString("/")}
            |${tab}Size: $size""".stripMargin
        }
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
        |announceList:\n    ${t.announceList.getOrElse(List.empty).map(_.mkString(", ")).mkString("\n    ")}
        |creationDate: ${t.creationDate.map(a => new Date(a * 1000).toLocalDate.toString).getOrElse("NA")}
        |comment: ${t.comment.getOrElse("NA")}
        |createdBy: ${t.createdBy.getOrElse("NA")}
        |""".stripMargin
    }
  }
}

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
                  .map {
                    case (startInclusive, endExclusive) => util.Arrays.copyOfRange(str, startInclusive, endExclusive)
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
              commonFields(dict).map {
                case (name, pieceLen, pieces) => // todo: There is validation I can do here!
                  SingleFile(length, name, pieceLen, pieces)
              }
            case (Some(a), None) => Left(DifferentTypeExpected("BInteger", a.getClass.toString))
            case (None, Some(bEncode)) =>
              (bEncode.as[List[File]], commonFields(dict)).mapN {
                case (files, (a, b, c)) => MultiFile(files, a, b, c)
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
