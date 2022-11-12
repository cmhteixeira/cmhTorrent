package com.cmhteixeira.bencode

sealed trait ParsingError

object ParsingError {
  case object BadInteger extends ParsingError
  case object DataAfterInteger extends ParsingError
  case object DataAfterByteString extends ParsingError
  case object DataAfterList extends ParsingError
  case object DataAfterDictionary extends ParsingError
  case object BadByteString extends ParsingError
  case object BadDictionary extends ParsingError
}
