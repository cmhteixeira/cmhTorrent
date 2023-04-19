package com.cmhteixeira.bittorrent

import java.net.InetSocketAddress
import scala.concurrent.Promise

package object tracker {

  private[tracker] case class State(peers: Set[InetSocketAddress], trackers: Map[InetSocketAddress, TrackerState]) {

    def newTrackerSent(socket: InetSocketAddress, connectSent: ConnectSent): State =
      State(peers, trackers = trackers + (socket -> connectSent))

    def toList: List[(InetSocketAddress, TrackerState)] = trackers.map { case (address, state) =>
      address -> state
    }.toList

    def updateEntry(tracker: InetSocketAddress, newState: TrackerState): State =
      copy(trackers = trackers.map {
        case (trackerSocket, _) if trackerSocket == tracker => (trackerSocket, newState)
        case pair => pair
      })

    def announceResponse(
        trackerSocket: InetSocketAddress,
        announceResponse: AnnounceResponse
    ): Option[AnnounceSent] =
      toList.collectFirst {
        case (thisTrackerSocket, announceSent @ AnnounceSent(txnId, _, _))
            if thisTrackerSocket == trackerSocket && txnId == announceResponse.transactionId =>
          announceSent
      }
  }

  private[tracker] object State {
    val empty: State = State(Set.empty, Map.empty)
  }

  private[tracker] sealed trait TrackerState

  private[tracker] case class ConnectSent(txnId: Int, channel: Promise[(ConnectResponse, Long)]) extends TrackerState

  private[tracker] case class AnnounceSent(txnId: Int, connectionId: Long, channel: Promise[AnnounceResponse])
      extends TrackerState

  private[tracker] case class AnnounceReceived(timestamp: Long, numPeers: Int) extends TrackerState

}
