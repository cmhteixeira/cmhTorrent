package com.cmhteixeira.bencode

trait Decoder[T] {
  def apply(t: Bencode): Either[DecodingFailure, T]
}
