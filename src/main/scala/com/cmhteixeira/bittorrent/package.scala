package com.cmhteixeira

import com.cmhteixeira.bencode.Bencode
import org.apache.commons.codec.binary.Hex
import sun.nio.cs.UTF_8

import java.security.MessageDigest
import java.util

package object bittorrent {

  sealed trait PeerId {
    val underlying: String
  }

  object PeerId {
    private case class PeerIdImpl(underlying: String) extends PeerId

    def apply(peerId: String): Option[PeerId] =
      if (peerId.getBytes(new UTF_8).length != 20) None
      else Some(PeerIdImpl(peerId))
  }

  sealed trait InfoHash {
    def hex: String
    def bytes: Array[Byte]

    override final def toString: String = hex

    override def equals(obj: Any): Boolean =
      if (!obj.isInstanceOf[InfoHash]) false else util.Arrays.equals(obj.asInstanceOf[InfoHash].bytes, bytes)
  }

  object InfoHash {

    def apply(bencode: Bencode): InfoHash = {
      val md: MessageDigest = MessageDigest.getInstance("SHA-1")
      val infoHash = md.digest(com.cmhteixeira.bencode.serialize(bencode))

      new InfoHash {
        override val hex: String = Hex.encodeHexString(infoHash)
        override val bytes: Array[Byte] = infoHash
      }
    }

    def apply(theBytes: Array[Byte]): Option[InfoHash] =
      if (theBytes.length != 20) None
      else
        Some(new InfoHash {
          override val hex: String = Hex.encodeHexString(theBytes)
          override val bytes: Array[Byte] = util.Arrays.copyOf(theBytes, theBytes.length)
        })
  }
}
