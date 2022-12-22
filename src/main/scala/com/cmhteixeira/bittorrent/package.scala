package com.cmhteixeira

import com.cmhteixeira.bencode.Bencode
import org.apache.commons.codec.binary.Hex
import sun.nio.cs.UTF_8

import java.net.URI
import java.security.MessageDigest
import java.util

package object bittorrent {

  case class PeerId(underlying: String)

  object PeerId {

    def apply(underlying: String): Option[PeerId] =
      if (underlying.getBytes(new UTF_8).length != 20) None
      else Some(new PeerId(underlying))
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

  /** Unresolved tracker socket.
    *
    * @param hostName
    * @param port
    */
  case class UdpSocket(hostName: String, port: Int)

  private[bittorrent] def parseToUdpSocketAddress(a: String): Either[String, UdpSocket] = {
    val url = new URI(a)
    (url.getScheme, url.getHost, url.getPort, url.getPath) match {
      case (_, host, port, _) if port > 0 & port <= Char.MaxValue => Right(UdpSocket(host, port))
      case (_, host, port, _) => Left(s"Port is '$port'. Host is $host.")
      case _ => Left("Some other error.")
    }
  }
}
