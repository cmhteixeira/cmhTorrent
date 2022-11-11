package com.cmhteixeira.bencode

sealed trait Bencode

object Bencode {
  case class BenInteger(underlying: Long) extends Bencode

  case class BenByteString(underlying: Array[Byte]) extends Bencode

  case class BenList(underlying: List[Bencode]) extends Bencode

  case class BenDictionary(underlying: Map[BenByteString, Bencode]) extends Bencode
}