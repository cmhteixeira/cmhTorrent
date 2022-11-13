package com.cmhteixeira.bencode

import sun.nio.cs.UTF_8

import java.nio.charset.Charset

sealed trait Bencode {
  def as[A](implicit ev: Decoder[A]): Either[DecodingFailure, A] = ev(this)

  def asString(charset: Charset): Option[String]

  def asString: Option[String] = asString(new UTF_8())

  def asNumber: Option[Bencode.BInteger]

  def asLong: Option[Long]

  def asList: Option[List[Bencode]]
}

object Bencode {

  case class BInteger(underlying: Long) extends Bencode {
    override def asString(charset: Charset): Option[String] = None
    override def asNumber: Option[BInteger] = Some(this)
    override def asList: Option[List[Bencode]] = None
    override def asLong: Option[Long] = Some(underlying)
  }

  case class BByteString(underlying: Array[Byte]) extends Bencode {
    override def asString(charset: Charset): Option[String] = Some(new String(underlying, charset))
    override def asNumber: Option[BInteger] = None
    override def asList: Option[List[Bencode]] = None
    override def asLong: Option[Long] = None
  }

  case class BList(underlying: List[Bencode]) extends Bencode {
    override def asString(charset: Charset): Option[String] = None
    override def asNumber: Option[BInteger] = None
    override def asList: Option[List[Bencode]] = Some(underlying)
    override def asLong: Option[Long] = None
  }

  case class BDictionary(underlying: Map[BByteString, Bencode]) extends Bencode {

    def apply(key: String): Option[Bencode] = apply(key, new UTF_8())

    def apply(key: String, charset: Charset): Option[Bencode] =
      underlying.find { case (string, _) => new String(string.underlying, charset) == key }.map(_._2)

    override def asString(charset: Charset): Option[String] = None
    override def asNumber: Option[BInteger] = None

    override def asList: Option[List[Bencode]] = None
    override def asLong: Option[Long] = None
  }
}
