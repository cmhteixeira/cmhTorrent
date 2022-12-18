package com.cmhteixeira.bittorrent.tracker

import cats.data.NonEmptyList
import com.cmhteixeira.bittorrent.InfoHash

import java.net.InetSocketAddress

trait Tracker {
  def peers(infoHash: InfoHash): List[Tracker.Peer]

  def submit(torrent: Torrent): Unit
}

object Tracker {
  case class Peer(socketAddress: InetSocketAddress) extends AnyVal

//  case class Torrent(
//      infoHash: InfoHash,
//      announce: InetSocketAddress,
//      announceList: Option[NonEmptyList[NonEmptyList[InetSocketAddress]]]
//  )
}
