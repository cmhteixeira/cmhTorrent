package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.{InfoHash, PeerId}
import com.cmhteixeira.bittorrent.tracker.AnnounceRequest.{Announce, Event}
import sun.nio.cs.UTF_8
import java.nio.ByteBuffer

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
    bytes.putInt(1) // equates to announce
    bytes.putInt(transactionId)
    bytes.put(infoHash.bytes)
    bytes.put(peerId.underlying.getBytes(new UTF_8))
    bytes.putLong(downloaded)
    bytes.putLong(left)
    bytes.putLong(uploaded)
    bytes.putInt(event match {
      case Event.None => 0
      case Event.Completed => 1
      case Event.Started => 2
      case Event.Stop => 3
    })
    bytes.putInt(ipAddress)
    bytes.putInt(key)
    bytes.putInt(numWanted)
    bytes.putShort(port)

    bytes.array()
  }
}

private[tracker] object AnnounceRequest {
  case object Announce
  sealed trait Event

  object Event {
    case object None extends Event
    case object Completed extends Event
    case object Started extends Event
    case object Stop extends Event
  }
}
