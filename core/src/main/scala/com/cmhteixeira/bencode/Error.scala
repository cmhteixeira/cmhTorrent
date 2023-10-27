package com.cmhteixeira.bencode

sealed trait Error extends Exception

object Error {
  sealed trait DecodingFailure extends Error

  object DecodingFailure {
    case object NotABdictionary extends DecodingFailure
    case object NotABList extends DecodingFailure
    case class DictionaryKeyMissing(keyName: String) extends DecodingFailure
    case class DifferentTypeExpected(expeted: String, actual: String) extends DecodingFailure
    case class GenericDecodingFailure(msg: String) extends DecodingFailure
  }

  sealed trait ParsingFailure extends Error

  object ParsingFailure {
    case object BadInteger extends ParsingFailure
    case object DataAfterInteger extends ParsingFailure
    case object DataAfterByteString extends ParsingFailure
    case object DataAfterList extends ParsingFailure
    case object DataAfterDictionary extends ParsingFailure
    case object BadByteString extends ParsingFailure
    case object BadDictionary extends ParsingFailure
  }
}
