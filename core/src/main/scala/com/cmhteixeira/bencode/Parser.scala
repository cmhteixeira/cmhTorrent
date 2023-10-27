package com.cmhteixeira.bencode

trait Parser {
  def parse(input: Array[Byte]): Either[Error.ParsingFailure, Bencode]
}
