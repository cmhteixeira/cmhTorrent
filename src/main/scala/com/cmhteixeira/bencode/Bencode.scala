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

  def asDict: Option[Map[BByteString, Bencode]]

  def merge(that: Bencode): Bencode =
    (this.asDict, that.asDict) match {
      case (Some(theThis), Some(theThat)) => BDictionary(theThis ++ theThat) // todo: how to deal with duplicate keys?
      case _ => that
    }

  final def toPrettyString: String = toPrettyString("")

  protected def toPrettyString(tab: String): String
}

object Bencode {

  case class BInteger(underlying: Long) extends Bencode {
    override def asString(charset: Charset): Option[String] = None
    override def asNumber: Option[BInteger] = Some(this)
    override def asList: Option[List[Bencode]] = None
    override def asLong: Option[Long] = Some(underlying)

    override def asDict: Option[Map[BByteString, Bencode]] =
      None
    override def toPrettyString(tab: String): String = s"${underlying}e"
  }

  case class BByteString(underlying: Array[Byte]) extends Bencode {
    override def asString(charset: Charset): Option[String] = Some(new String(underlying, charset))
    override def asNumber: Option[BInteger] = None
    override def asList: Option[List[Bencode]] = None
    override def asLong: Option[Long] = None

    override def asDict: Option[Map[BByteString, Bencode]] =
      None

    override def toPrettyString(tab: String): String =
      s"${underlying.length}:${new String(underlying, new UTF_8())}[UTF8]"
  }

  case class BList(underlying: List[Bencode]) extends Bencode {
    override def asString(charset: Charset): Option[String] = None
    override def asNumber: Option[BInteger] = None
    override def asList: Option[List[Bencode]] = Some(underlying)
    override def asLong: Option[Long] = None

    override def asDict: Option[Map[BByteString, Bencode]] =
      None

    override def toPrettyString(tab: String): String = {
      val res = underlying.zipWithIndex.map { case (a, b) => s"$tab$b: ${a.toPrettyString(tab)}" }.mkString("\n")
      s"""${tab}List(l)
         |  $res
         |${tab}End(e)
         |""".stripMargin
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

    override def asDict: Option[Map[BByteString, Bencode]] =
      Some(underlying)

    override def toPrettyString(tab: String): String = {
      val res =
        underlying.map { case (a, b) => s"$tab${a.toPrettyString(tab)}: ${b.toPrettyString(tab)}" }.mkString("\n")
      s""" Dict(d)
        |  $res
        |End(e)
        |""".stripMargin
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

  implicit val show: Show[Bencode] = new Show[Bencode] {

    override def show(t: Bencode): String = t.toPrettyString
  }

  implicit val json: io.circe.Encoder[Bencode] = new circe.Encoder[Bencode] {
    override def apply(a: Bencode): Json = ???
  }
}
