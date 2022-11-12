package com.cmhteixeira.bencode

trait Encoder[T] {
  def apply(t: T): Bencode
}
