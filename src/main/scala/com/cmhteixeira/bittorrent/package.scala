package com.cmhteixeira

package object bittorrent {
  def serialize[A](a: A)(implicit ev: Serializer[A]): Array[Byte] = ev(a)
  def deserialize[A](in: Array[Byte])(implicit ev: Deserializer[A]): Either[String, A] = ev(in)
}
