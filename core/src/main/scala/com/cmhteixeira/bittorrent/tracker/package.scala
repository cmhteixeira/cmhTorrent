package com.cmhteixeira.bittorrent

import java.net.InetSocketAddress
import scala.concurrent.Promise

package object tracker {

  private[tracker] sealed trait State {

    def newTrackerSent(socket: InetSocketAddress, connectSent: ConnectSent): Tiers[TrackerState] =
      this match {
        case Submitted => Tiers(Set.empty, Map(socket -> connectSent))
        case tiers @ Tiers(_, resolved, _) => tiers.copy(underlying = resolved + (socket -> connectSent))
      }

    def newTrackerUnresolved(udpSocket: UdpSocket): Tiers[TrackerState] =
      this match {
        case Submitted => Tiers(Set.empty, Map.empty, Set(udpSocket))
        case tiers @ Tiers(_, _, unresolved) => tiers.copy(unresolved = unresolved + udpSocket)
      }

  }

  private[tracker] case object Submitted extends State

  private[tracker] case class Tiers[+A <: TrackerState](
      peers: Set[InetSocketAddress],
      underlying: Map[InetSocketAddress, A],
      unresolved: Set[UdpSocket] = Set.empty
  ) extends State {

    def toList: List[(InetSocketAddress, A)] = underlying.map { case (address, state) => address -> state }.toList

    def updateEntry(tracker: InetSocketAddress, newState: TrackerState): Tiers[TrackerState] =
      copy(underlying = underlying.map {
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

  private[tracker] sealed trait TrackerState

  private[tracker] case class ConnectSent(txnId: Int, channel: Promise[(ConnectResponse, Long)]) extends TrackerState

  private[tracker] case class AnnounceSent(txnId: Int, connectionId: Long, channel: Promise[AnnounceResponse])
      extends TrackerState

  private[tracker] case class AnnounceReceived(timestamp: Long, numPeers: Int) extends TrackerState

}
