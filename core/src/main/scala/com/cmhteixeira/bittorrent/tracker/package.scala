package com.cmhteixeira.bittorrent

import java.net.InetSocketAddress
import scala.concurrent.Promise

package object tracker {

  private[tracker] sealed trait State {

    def newTrackerSent(socket: InetSocketAddress, connectSent: ConnectSent): Tiers[TrackerState] =
      this match {
        case Submitted => Tiers(Map(socket -> connectSent))
        case Tiers(resolved, unresolved) => Tiers(underlying = resolved + (socket -> connectSent), unresolved)
      }

    def newTrackerUnresolved(udpSocket: UdpSocket): Tiers[TrackerState] =
      this match {
        case Submitted => Tiers(Map.empty, Set(udpSocket))
        case Tiers(resolved, unresolved) => Tiers(resolved, unresolved + udpSocket)
      }

  }

  private[tracker] case object Submitted extends State

  private[tracker] case class Tiers[+A <: TrackerState](
      underlying: Map[InetSocketAddress, A],
      unresolved: Set[UdpSocket] = Set.empty
  ) extends State {

    def toList: List[(InetSocketAddress, A)] = underlying.map { case (address, state) => address -> state }.toList

    def updateEntry(tracker: InetSocketAddress, newState: TrackerState): Tiers[TrackerState] =
      Tiers(underlying.map {
        case (trackerSocket, _) if trackerSocket == tracker => (trackerSocket, newState)
        case pair => pair
      })

    def get(trackerSocket: InetSocketAddress): Option[A] = toList.find { case (a, _) => a == trackerSocket }.map(_._2)

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

  private[tracker] case class AnnounceSent(
      txnId: Int,
      connectionId: Long,
      channel: Promise[AnnounceResponse]
  ) extends TrackerState

  private[tracker] case class AnnounceReceived(leechers: Int, seeders: Int, peers: List[InetSocketAddress])
      extends TrackerState
}
