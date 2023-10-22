package com.cmhteixeira.bittorrent.tracker

import cats.data.NonEmptyList
import cats.implicits.catsSyntaxTuple2Semigroupal
import com.cmhteixeira.bittorrent.UdpSocket.parseToUdpSocketAddress
import com.cmhteixeira.bittorrent.{InfoHash, UdpSocket}

case class Torrent(
    infoHash: InfoHash,
    announce: UdpSocket,
    announceList: Option[NonEmptyList[NonEmptyList[UdpSocket]]]
)

object Torrent {

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
