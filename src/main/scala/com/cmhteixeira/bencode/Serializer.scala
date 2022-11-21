package com.cmhteixeira.bencode

trait Serializer {
  def serialize(bencode: Bencode): Array[Byte]
}
