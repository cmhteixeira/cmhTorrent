package com.cmhteixeira.bittorrent

trait Serializer[T] {
  def apply(t: T): Array[Byte]
}
