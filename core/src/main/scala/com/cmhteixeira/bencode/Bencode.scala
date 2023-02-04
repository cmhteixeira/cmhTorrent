package com.cmhteixeira.bencode

import cats.Show
import com.cmhteixeira.bencode.Bencode.{BByteString, BDictionary}
import io.circe
import io.circe.Decoder.Result
import io.circe.{HCursor, Json}
import sun.nio.cs.UTF_8

import java.nio.charset.Charset

sealed trait Bencode {
  def as[A](implicit ev: Decoder[A]): Either[DecodingFailure, A] = ev(this)

  def asString(charset: Charset): Option[String]

  final def asString: Option[String] = asString(new UTF_8())

  def asNumber: Option[Bencode.BInteger]

  def asLong: Option[Long]

  def asList: Option[List[Bencode]]

  def asDict: Option[BDictionary]

  def merge(that: Bencode): Bencode =
    (this.asDict.map(_.underlying), that.asDict.map(_.underlying)) match {
      case (Some(theThis), Some(theThat)) => BDictionary(theThis ++ theThat) // todo: how to deal with duplicate keys?
      case _ => that
    }

  override final def toString: String = toPrettyString("")

  protected def toPrettyString(tab: String): String
}

object Bencode {

  case class BInteger(underlying: Long) extends Bencode {
    override def asString(charset: Charset): Option[String] = None
    override def asNumber: Option[BInteger] = Some(this)
    override def asList: Option[List[Bencode]] = None
    override def asLong: Option[Long] = Some(underlying)

    override def asDict: Option[BDictionary] = None
    override def toPrettyString(tab: String): String = s"l${underlying}e"
  }

  case class BByteString(underlying: Array[Byte]) extends Bencode {
    override def asString(charset: Charset): Option[String] = Some(new String(underlying, charset))
    override def asNumber: Option[BInteger] = None
    override def asList: Option[List[Bencode]] = None
    override def asLong: Option[Long] = None

    override def asDict: Option[BDictionary] =
      None

    override def toPrettyString(tab: String): String =
      s"${new String(underlying, new UTF_8())}"
  }

  case class BList(underlying: List[Bencode]) extends Bencode {
    override def asString(charset: Charset): Option[String] = None
    override def asNumber: Option[BInteger] = None
    override def asList: Option[List[Bencode]] = Some(underlying)
    override def asLong: Option[Long] = None

    override def asDict: Option[BDictionary] =
      None

    override def toPrettyString(tab: String): String = {
//      val res2 =
//        underlying
//          .map {
//            case (key, value) =>
//              val keyPretty = key.toPrettyString(tab)
//              s"${tab + " " * 2}$keyPretty: ${value.toPrettyString(tab + " " * (2 + keyPretty.length + 2))}"
//          }
//          .mkString("\n")
//

      val res =
        underlying.zipWithIndex
          .map {
            case (bencode, index) =>
              val indexStr: String = index.toString
              s"${tab + " " * 2}$indexStr: ${bencode.toPrettyString(tab + " " * (2 + indexStr.length + 2))}"
          }
          .mkString("\n")
      s"""LIST(l)
         |$res
         |${tab}END(e)""".stripMargin
    }
  }

  case class BDictionary(underlying: Map[BByteString, Bencode]) extends Bencode {

    def apply(key: String): Option[Bencode] = apply(key, new UTF_8())

    def apply(key: String, charset: Charset): Option[Bencode] =
      underlying.find { case (string, _) => new String(string.underlying, charset) == key }.map(_._2)

    override def asString(charset: Charset): Option[String] = None
    override def asNumber: Option[BInteger] = None

    override def asList: Option[List[Bencode]] = None
    override def asLong: Option[Long] = None

    override def asDict: Option[BDictionary] = Some(this)

    override def toPrettyString(tab: String): String = {

      val res =
        underlying
          .map {
            case (key, value) =>
              val keyPretty = key.toPrettyString(tab)
              s"${tab + " " * 2}$keyPretty: ${value.toPrettyString(tab + " " * (2 + keyPretty.length + 2))}"
          }
          .mkString("\n")
      s"""DICT(d)
         |$res
         |${tab}END(e)""".stripMargin
    }
  }

  def fromString(value: String): BByteString = fromString(value, new UTF_8())
  def fromString(value: String, charset: Charset): BByteString = BByteString(value.getBytes(charset))

  def fromLong(value: Long): BInteger = BInteger(value)

  def dictStringKeys(values: (String, Bencode)*): BDictionary =
    BDictionary(
      values.toMap.map { case (keyStr, value) => Bencode.fromString(keyStr) -> value }
    )

  def dict(values: (Array[Byte], Bencode)*): BDictionary =
    BDictionary(values.map { case (key, value) => BByteString(key) -> value }.toMap)

  implicit val show: Show[Bencode] = Show.fromToString

  implicit val json: io.circe.Encoder[Bencode] = new circe.Encoder[Bencode] {

    override def apply(a: Bencode): Json = //todo: optimize this
      a match {
        case BInteger(underlying) => Json.fromLong(underlying)
        case BByteString(underlying) => Json.fromString(new String(underlying, new UTF_8()))
        case BList(underlying) => Json.fromValues(underlying.map(apply))
        case BDictionary(underlying) =>
          Json.fromFields(underlying.map {
            case (BByteString(key), value) => (new String(key, new UTF_8()), apply(value))
          })
      }
  }

}
