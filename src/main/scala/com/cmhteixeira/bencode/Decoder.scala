package com.cmhteixeira.bencode

import cats.implicits.toTraverseOps
import com.cmhteixeira.bencode.DecodingFailure.NotABList

trait Decoder[T] {
  def apply(t: Bencode): Either[DecodingFailure, T]
}

object Decoder {

  implicit def listDecoder[A](implicit ev: Decoder[A]): Decoder[List[A]] =
    new Decoder[List[A]] {

      override def apply(
          t: Bencode
      ): Either[DecodingFailure, List[A]] =
        t match {
          case Bencode.BList(underlying) => underlying.traverse(ev(_))
          case _ => Left(NotABList)
        }
    }
}
