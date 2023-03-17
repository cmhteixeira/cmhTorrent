package com.cmhteixeira.bittorrent.tracker

import com.cmhteixeira.bittorrent.InfoHash

import java.net.InetSocketAddress

trait Tracker {
  def peers(infoHash: InfoHash): List[InetSocketAddress]

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

    def addConnectReceived(tracker: InetSocketAddress): Statistics =
      Statistics(
        summary =
          summary.copy(totalTrackers = summary.totalTrackers + 1, connectionReceived = summary.connectionReceived + 1),
        trackers = trackers + (tracker -> TrackerState.ConnectReceived)
      )

    def addAnnounceSent(tracker: InetSocketAddress): Statistics =
      Statistics(
        summary = summary.copy(totalTrackers = summary.totalTrackers + 1, announceSent = summary.announceSent + 1),
        trackers = trackers + (tracker -> TrackerState.AnnounceSent)
      )

    def addAnnounceReceived(tracker: InetSocketAddress, trackerPeers: Int, numNewPeers: Int): Statistics =
      Statistics(
        summary = summary.copy(
          totalTrackers = summary.totalTrackers + 1,
          distinctPeers = numNewPeers,
          announceReceived = summary.announceReceived + 1
        ),
        trackers = trackers + (tracker -> TrackerState.AnnounceReceived(trackerPeers))
      )
  }

  case class Summary(
      totalTrackers: Int,
      connectionSent: Int,
      connectionReceived: Int,
      announceSent: Int,
      announceReceived: Int,
      distinctPeers: Int
  )
  sealed trait TrackerState

  object TrackerState {

    case object ConnectSent extends TrackerState
    case object ConnectReceived extends TrackerState
    case object AnnounceSent extends TrackerState
    case class AnnounceReceived(numberPeers: Int) extends TrackerState
  }
}
