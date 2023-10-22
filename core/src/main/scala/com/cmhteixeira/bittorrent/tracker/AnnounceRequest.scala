package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.{InfoHash, PeerId}
import com.cmhteixeira.bittorrent.tracker.AnnounceRequest.{Announce, Event}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private[tracker] case class AnnounceRequest(
    connectionId: Long,
    action: Announce.type,
    transactionId: Int,
    infoHash: InfoHash,
    peerId: PeerId,
    downloaded: Long,
    left: Long,
    uploaded: Long,
    event: Event,
    ipAddress: Int,
    key: Int,
    numWanted: Int,
    port: Short
) {

  def serialize: Array[Byte] = {
    val bytes = ByteBuffer.allocate(98)
    bytes.putLong(connectionId)
    bytes.putInt(action match {
      case Announce => 1 // equates to announce
    })
    bytes.putInt(transactionId)
    bytes.put(infoHash.bytes)
    bytes.put(peerId.underlying.getBytes(StandardCharsets.UTF_8))
    bytes.putLong(downloaded)
    bytes.putLong(left)
    bytes.putLong(uploaded)
    bytes.putInt(event match {
      case AnnounceRequest.None => 0
      case AnnounceRequest.Completed => 1
      case AnnounceRequest.Started => 2
      case AnnounceRequest.Stop => 3
    })
    bytes.putInt(ipAddress)
    bytes.putInt(key)
    bytes.putInt(numWanted)
    bytes.putShort(port)

    bytes.array()
  }
}

object AnnounceRequest {
  case object Announce
  sealed trait Event

  case object None extends Event
  case object Completed extends Event
  case object Started extends Event
  case object Stop extends Event
}
