package com.cmhteixeira.cmhtorrent

import com.cmhteixeira.bencode.{Bencode, Decoder, DecodingFailure}

case class Torrent(
    info: Info,
    announce: String,
    announceList: Option[String],
    creationDate: Option[Long],
    comment: Option[String],
    createdBy: Option[String]
)

sealed trait Info

case class SingleFile(length: Long, md5sum: Option[String], name: String, pieceLength: Long, pieces: String)
    extends Info

case class MultiFile(files: List[File], md5sum: Option[String], name: String, pieceLength: Long, pieces: String)
    extends Info

case class File(length: Long, path: String)

object Torrent {

  implicit val decoder: Decoder[Torrent] = new Decoder[Torrent] {

    override def apply(
        t: Bencode
    ): Either[DecodingFailure, Torrent] =
      t match {
        case Bencode.BenDictionary(underlying) => ???
        case _ => Left(DecodingFailure.NotABdictionary)
      }
  }
}

object Info {

  implicit val decoder: Decoder[Info] = new Decoder[Info] {

    override def apply(
        t: Bencode
    ): Either[DecodingFailure, Info] = ???
  }
}
