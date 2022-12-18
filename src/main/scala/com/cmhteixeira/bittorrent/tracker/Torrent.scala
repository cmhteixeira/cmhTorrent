package com.cmhteixeira.bittorrent.tracker

import cats.data.NonEmptyList
import cats.implicits.catsSyntaxTuple2Semigroupal
import com.cmhteixeira.bittorrent.InfoHash

import java.net.{InetSocketAddress, URI}

case class Torrent(
    infoHash: InfoHash,
    announce: InetSocketAddress,
    announceList: Option[NonEmptyList[NonEmptyList[InetSocketAddress]]]
)

object Torrent {

  //todo: Rethink. There is better way.
  private def parseToUdpSocketAddress(a: String): Either[String, InetSocketAddress] = {
    val url = new URI(a)
    (url.getScheme, url.getHost, url.getPort, url.getPath) match {
      case (_, host, port, _) if port > 0 & port <= Char.MaxValue => Right(new InetSocketAddress(host, port))
      case (_, host, port, _) => Left(s"Port is '$port'. Host is $host.")
      case _ => Left("Some other error.")
    }
  }

  //todo: Rethink. There is better way.
  def apply(infoHash: InfoHash, torrent: com.cmhteixeira.cmhtorrent.Torrent): Either[String, Torrent] = {
    val newAnnounceList = torrent.announceList match {
      case Some(announceList) =>
        val t = announceList
          .map { a =>
            val validTrackerURls = a.map(parseToUdpSocketAddress).collect {
              case Right(trackerSocket) => trackerSocket
            }
            NonEmptyList.fromList(validTrackerURls).toRight("Empty list inner.")
          }
          .collect { case Right(tier) => tier }

        NonEmptyList.fromList(t).toRight("Empty list outer").map(a => Option(a))
      case None => Right(None)
    }

    (parseToUdpSocketAddress(torrent.announce), newAnnounceList)
      .mapN { case (announce, announceList) => new Torrent(infoHash, announce, announceList) }
  }
}
