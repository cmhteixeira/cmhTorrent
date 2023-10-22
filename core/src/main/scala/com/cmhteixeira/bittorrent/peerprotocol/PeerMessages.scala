package com.cmhteixeira.bittorrent.peerprotocol

import com.cmhteixeira.bittorrent.{InfoHash, PeerId}
import com.cmhteixeira.bittorrent.peerprotocol.PeerMessages.Handshake.protocol
import scodec.bits.ByteVector

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private[peerprotocol] object PeerMessages {

  case class Handshake(infoHash: InfoHash, peerId: PeerId) {

    def serialize: Array[Byte] = {
      val handShake = ByteBuffer.allocate(68)
      handShake.put(19: Byte)
      handShake.put(protocol.getBytes(StandardCharsets.US_ASCII))
      handShake.putLong(0)
      handShake.put(infoHash.bytes)
      handShake.put(peerId.underlying.getBytes(StandardCharsets.UTF_8))
      handShake.array()
    }
  }

  object Handshake {
    private val protocol: String = "BitTorrent protocol"
  }

  case class Request(index: Int, begin: Int, length: Int) {
    def serialize: Array[Byte] =
      ByteBuffer.allocate(17).putInt(13).put(0x6: Byte).putInt(index).putInt(begin).putInt(length).array()

  }

  case object Unchoke {
    lazy val serialize: ByteVector = ByteVector(ByteBuffer.allocate(5).putInt(1).put(0x1: Byte).array())
  }

  case class Have(idx: Int) {
    lazy val serialize: ByteVector = ByteVector(ByteBuffer.allocate(9).putInt(9).put(0x4: Byte).putInt(idx).array())
  }

}
