package com.cmhteixeira.bittorrent

trait Deserializer[T] {
  def apply(in: Array[Byte]): Either[String, T]
}
