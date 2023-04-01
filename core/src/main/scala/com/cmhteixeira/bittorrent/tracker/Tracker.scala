package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.InfoHash

import java.net.InetSocketAddress

trait Tracker {
  def peers(infoHash: InfoHash): Set[InetSocketAddress]

  def statistics: Map[InfoHash, Tracker.Statistics]

  def submit(torrent: Torrent): Unit
}

object Tracker {

  case class Statistics(summary: Summary, trackers: Map[InetSocketAddress, TrackerState]) {

    def addConnectSent(tracker: InetSocketAddress): Statistics =
      Statistics(
        summary = summary.copy(totalTrackers = summary.totalTrackers + 1, connectionSent = summary.connectionSent + 1),
        trackers = trackers + (tracker -> TrackerState.ConnectSent)
      )

    def addAnnounceSent(tracker: InetSocketAddress): Statistics =
      Statistics(
        summary = summary.copy(totalTrackers = summary.totalTrackers + 1, announceSent = summary.announceSent + 1),
        trackers = trackers + (tracker -> TrackerState.AnnounceSent)
      )

    def addAnnounceReceived(tracker: InetSocketAddress, numNewPeers: Int): Statistics =
      Statistics(
        summary =
          summary.copy(totalTrackers = summary.totalTrackers + 1, announceReceived = summary.announceReceived + 1),
        trackers = trackers + (tracker -> TrackerState.AnnounceReceived(numNewPeers))
      )

    def setNumberPeers(numPeers: Int): Statistics = copy(summary = summary.copy(distinctPeers = numPeers))
  }

  case class Summary(
      totalTrackers: Int,
      connectionSent: Int,
      announceSent: Int,
      announceReceived: Int,
      distinctPeers: Int
  )
  sealed trait TrackerState

  object TrackerState {

    case object ConnectSent extends TrackerState
    case object AnnounceSent extends TrackerState
    case class AnnounceReceived(numberPeers: Int) extends TrackerState
  }
}
