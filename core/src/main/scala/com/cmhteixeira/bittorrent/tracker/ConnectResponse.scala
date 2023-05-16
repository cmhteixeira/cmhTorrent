package com.cmhteixeira.bittorrent.tracker

import java.nio.ByteBuffer
import scala.util.{Failure, Success, Try}

case class ConnectResponse(transactionId: Int, connectionId: Long)

object ConnectResponse {

  def deserialize(in: Array[Byte]): Either[String, ConnectResponse] = {
    val byteBuffer = ByteBuffer.wrap(in)
    (for {
      action <- Try(byteBuffer.getInt)
      transactionId <- Try(byteBuffer.getInt)
      connectionId <- Try(byteBuffer.getLong)
      _ <- if (action == 0) Success(()) else Failure(new IllegalArgumentException("Action is not zero"))
    } yield ConnectResponse.apply(transactionId, connectionId)) match {
      case Failure(exception) => Left(exception.toString)
      case Success(value) => Right(value)
    }
  }

  def deserializeJava(in: Array[Byte]): ConnectResponse =
    deserialize(in).fold(
      error => throw new IllegalArgumentException(s"Error deserializing into connect response: '$error'."),
      identity
    )
}
