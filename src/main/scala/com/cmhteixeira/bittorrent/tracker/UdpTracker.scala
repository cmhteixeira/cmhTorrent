package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent._

trait UdpTracker {

  def connect(transactionId: Int): Either[UdpTracker.Error, UdpConnected]
}

object UdpTracker {
  sealed trait Error
  case class DeserializationError(msg: String) extends Error
  case class NotReceived(timeoutMillis: Int, timesRetried: Short) extends Error

  case class IncorrectResponseTransactionId(expected: Int, actual: Int) extends Error

  case class SomeRandomError(msg: String) extends Error
}






