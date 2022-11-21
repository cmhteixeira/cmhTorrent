package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.Deserializer

import java.nio.ByteBuffer
import scala.util.{Failure, Success, Try}

sealed trait ConnectResponse {
  def action: ConnectRequest.Connect.type
  def transactionId: Int
  def connectionId: Long
}

object ConnectResponse {

  private case class ConnectResponseImpl(action: ConnectRequest.Connect.type, transactionId: Int, connectionId: Long)
      extends ConnectResponse
  def apply(in: Array[Byte]): Either[String, ConnectResponse] = deserializer(in)

  def apply(theTransactionId: Int, theConnectionId: Long): ConnectResponse =
    ConnectResponseImpl(ConnectRequest.Connect, theTransactionId, theConnectionId)

  implicit val deserializer: Deserializer[ConnectResponse] = new Deserializer[ConnectResponse] {

    override def apply(
        in: Array[Byte]
    ): Either[String, ConnectResponse] = {
      val byteBuffer = ByteBuffer.wrap(in)
      (for {
        action <- Try(byteBuffer.getInt(0))
        transantionId <- Try(byteBuffer.getInt(4))
        connectionId <- Try(byteBuffer.getLong(8))
        _ <- if (action == 0) Success(()) else Failure(new IllegalArgumentException("Action is not zero"))
      } yield ConnectResponse.apply(transantionId, connectionId)) match {
        case Failure(exception) => Left(exception.toString)
        case Success(value) => Right(value)
      }
    }
  }
}
