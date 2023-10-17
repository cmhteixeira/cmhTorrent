package com.cmhteixeira

import com.cmhteixeira.bencode.Bencode

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

package bittorrent {
  import scodec.bits.ByteVector

  case class PeerId(underlying: String) extends AnyVal

  object PeerId {

    def apply(underlying: String): Option[PeerId] =
      if (underlying.getBytes(StandardCharsets.UTF_8).length != 20) None
      else Some(new PeerId(underlying))
  }

  sealed trait InfoHash {
    def hex: String
    def bytes: Array[Byte]
    override final def toString: String = hex
  }

  object InfoHash {
    private case class InfoHashImpl(immutableBytes: ByteVector) extends InfoHash {
      override def hex: String = immutableBytes.toHex
      override def bytes: Array[Byte] = immutableBytes.toArray
    }

    def apply(bencode: Bencode): InfoHash = {
      val md: MessageDigest = MessageDigest.getInstance("SHA-1")
      InfoHashImpl(ByteVector(md.digest(com.cmhteixeira.bencode.serialize(bencode))))
    }

    def apply(theBytes: Array[Byte]): Option[InfoHash] =
      if (theBytes.length != 20) None
      else Some(InfoHashImpl(ByteVector(theBytes)))
  }

  /** Unresolved tracker socket.
    *
    * @param hostName
    * @param port
    */
  case class UdpSocket(hostName: String, port: Int)

  object UdpSocket {
    def parseToUdpSocketAddress(a: String): Either[String, UdpSocket] = {
      val url = new URI(a)
      (url.getScheme, url.getHost, url.getPort, url.getPath) match {
        case (_, host, port, _) if port > 0 & port <= Char.MaxValue => Right(UdpSocket(host, port))
        case (_, host, port, _) => Left(s"Port is '$port'. Host is $host.")
        case _ => Left("Some other error.")
      }
    }
  }
}
