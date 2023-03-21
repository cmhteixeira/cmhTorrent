package com.cmhteixeira.bittorrent

import java.net.InetSocketAddress
import java.util.concurrent.ScheduledFuture
import scala.concurrent.Promise

package object tracker {

  private[tracker] sealed trait State {

    def newTrackerSent(socket: InetSocketAddress, connectSent: ConnectSent): Option[Tiers[TrackerState]] =
      this match {
        case Submitted => Some(Tiers(Map(socket -> connectSent)))
        case Tiers(resolved, unresolved) =>
          if (resolved.contains(socket)) None
          else Some(Tiers(underlying = resolved + (socket -> connectSent), unresolved))
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

    def connectResponse(
        trackerSocket: InetSocketAddress,
        connectResponse: ConnectResponse
    ): Option[ConnectSent] =
      toList
        .collectFirst {
          case (thisTrackerSocket, a @ ConnectSent(thisTxnId, _, _, _))
              if thisTrackerSocket == trackerSocket && thisTxnId == connectResponse.transactionId =>
            a
        }

    def announceResponse(
        trackerSocket: InetSocketAddress,
        announceResponse: AnnounceResponse
    ): Option[AnnounceSent] =
      toList.collectFirst {
        case (thisTrackerSocket, a @ AnnounceSent(txnId, _, _, _, _))
            if thisTrackerSocket == trackerSocket && txnId == announceResponse.transactionId =>
          a
      }
  }

  private[tracker] sealed trait TrackerState

  private[tracker] case class ConnectSent(txnId: Int, channel: Promise[Unit], checkTask: ScheduledFuture[_], n: Int)
      extends TrackerState

  private[tracker] case class ConnectReceived(connectionId: Long, timestamp: Long) extends TrackerState

  private[tracker] case class AnnounceSent(
      txnId: Int,
      connectionId: Long,
      timestampConnectionId: Long,
      checkTask: ScheduledFuture[_],
      n: Int
  ) extends TrackerState

  private[tracker] case class AnnounceReceived(leechers: Int, seeders: Int, peers: List[InetSocketAddress])
      extends TrackerState
}
