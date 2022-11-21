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
import io.circe.{Json, JsonObject}
import sun.nio.cs.UTF_8

import java.sql.Date

case class Torrent(
    info: Info,
    announce: String,
    announceList: Option[List[List[String]]],
    creationDate: Option[Long],
    comment: Option[String],
    createdBy: Option[String]
)

sealed trait Info

case class SingleFile(length: Long, md5sum: Option[String], name: String, pieceLength: Long, pieces: String)
    extends Info

case class MultiFile(files: List[File], md5sum: Option[String], name: String, pieceLength: Long, pieces: String)
    extends Info

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
         |  md5sum: ${t.md5sum.getOrElse("NA")}
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
         |  md5sum: ${t.md5sum.getOrElse("NA")}
         |  pieceLength: ${t.pieceLength}
         |  pieces: ${t.pieces.take(15)} [truncated]""".stripMargin

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

    private def commonFields(dict: BDictionary): Either[DecodingFailure, (String, Long, String)] = {
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
            str <- value.asString.toRight(GenericDecodingFailure("asd"))
          } yield str
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
              commonFields(dict).map { case (a, b, c) => SingleFile(length, None, a, b, c) }
            case (Some(a), None) => Left(DifferentTypeExpected("BInteger", a.getClass.toString))
            case (None, Some(bEncode)) =>
              (bEncode.as[List[File]], commonFields(dict)).mapN {
                case (files, (a, b, c)) => MultiFile(files, None, a, b, c)
              }
          }
        case _ => Left(DecodingFailure.NotABdictionary)
      }
  }

  implicit val encoder: Encoder[Info] = new Encoder[Info] {

    private def commonFields(name: String, pieceLength: Long, pieces: String, md5sum: Option[String]): BDictionary =
      Bencode.dictStringKeys(
        (nameField, Bencode.fromString(name)),
        (pieceLengthField, Bencode.fromLong(pieceLength)),
        (piecesField, Bencode.fromString(pieces))
      )

    override def apply(t: Info): Bencode =
      t match {
        case SingleFile(length, md5sum, name, pieceLength, pieces) =>
          BDictionary(Map(Bencode.fromString(lengthField) -> Bencode.fromLong(length)))
            .merge(commonFields(name, pieceLength, pieces, md5sum))
        case MultiFile(files, md5sum, name, pieceLength, pieces) =>
          Bencode
            .dictStringKeys((filesField, BList(files.map(file => File.encoder(file)))))
            .merge(commonFields(name, pieceLength, pieces, md5sum))
      }
  }
}
