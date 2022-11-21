package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bencode
import com.cmhteixeira.bencode.Bencode
import com.cmhteixeira.bencode.Bencode.BDictionary
import com.cmhteixeira.bittorrent.Serializer
import com.cmhteixeira.bittorrent.tracker.AnnounceRequest.{Announce, Event}
import com.cmhteixeira.cmhtorrent.Info
import sun.nio.cs.{US_ASCII, UTF_8}

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import org.apache.commons.codec.binary.Hex;

/**
  *
  */
sealed trait AnnounceRequest {
  def connectionId: Long
  def action: Announce.type
  def transactionId: Int
  def infoHash: Array[Byte]
  def peerId: String
  def downloaded: Long
  def left: Long
  def uploaded: Long
  def event: Event

  /** IP address of client. If 0, tracker will assume the IP of the sender of the UDP packet */
  def ipAddress: Int

  /** Random key. Unknown purpose */
  def key: Int

  /** Maximum number of peers to send in the reply. -1 for default*/
  def numWanted: Int
  def port: Short
}

object AnnounceRequest {
  case object Announce
  sealed trait Event

  object Event {
    case object None extends Event
    case object Completed extends Event
    case object Started extends Event
    case object Stop extends Event
  }

  private case class AnnounceRequestImpl(
      connectionId: Long,
      action: Announce.type,
      transactionId: Int,
      infoHash: Array[Byte], // or byte array?
      peerId: String,
      downloaded: Long,
      left: Long,
      uploaded: Long,
      event: Event,
      ipAddress: Int,
      key: Int,
      numWanted: Int, //what is this?
      port: Short
  ) extends AnnounceRequest

  def apply(
      connectionId: Long,
      transactionId: Int,
      originalInfoValue: Bencode,
      peerId: String,
      downloaded: Long,
      left: Long,
      uploaded: Long,
      event: Event,
      ipAddress: Int,
      key: Int,
      numWanted: Int, //what is this?
      port: Short
  ): Either[String, AnnounceRequest] = {
    val md: MessageDigest = MessageDigest.getInstance("SHA-1")
    val infoHash = md.digest(bencode.serialize(originalInfoValue))
    val peerIdSize = peerId.getBytes.length
    if (peerIdSize != 20) Left("asd")
    else if (infoHash.length != 20) Left("Not exactly 20")
    else
      Right(
        AnnounceRequestImpl(
          connectionId,
          Announce,
          transactionId,
          infoHash,
          peerId,
          downloaded,
          left,
          uploaded,
          event,
          ipAddress,
          key,
          numWanted,
          port
        )
      )
  }

  implicit val serializer: Serializer[AnnounceRequest] = new Serializer[AnnounceRequest] {

    override def apply(t: AnnounceRequest): Array[Byte] = {
      val bytes = ByteBuffer.allocate(98)
      bytes.putLong(t.connectionId)
      bytes.putInt(1) // equates to announce
      bytes.putInt(t.transactionId)
      bytes.put(t.infoHash)
      bytes.put(t.peerId.getBytes)
      bytes.putLong(t.downloaded)
      bytes.putLong(t.left)
      bytes.putLong(t.uploaded)
      bytes.putInt(t.event match {
        case Event.None => 0
        case Event.Completed => 1
        case Event.Started => 2
        case Event.Stop => 3
      })
      bytes.putInt(t.ipAddress)
      bytes.putInt(t.key)
      bytes.putInt(t.numWanted)
      bytes.putShort(t.port)

      bytes.array()
    }
  }
}

//Offset  Size    Name    Value
//0       64-bit integer  connection_id
//8       32-bit integer  action          1 // announce
//12      32-bit integer  transaction_id
//16      20-byte string  info_hash
//36      20-byte string  peer_id
//56      64-bit integer  downloaded
//64      64-bit integer  left
//72      64-bit integer  uploaded
//80      32-bit integer  event           0 // 0: none; 1: completed; 2: started; 3: stopped
//84      32-bit integer  IP address      0 // default
//88      32-bit integer  key
//92      32-bit integer  num_want        -1 // default
//96      16-bit integer  port
//98
