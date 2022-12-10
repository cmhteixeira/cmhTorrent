package com.cmhteixeira.bittorrent

import cats.data.NonEmptyList

import java.net.InetSocketAddress
import scala.concurrent.Promise
package object tracker {

  private[tracker] case class Tiers(tried: List[Tier], current: CurrentTier, next: List[Tier]) {

    def possibleConnectRes(
        peer: InetSocketAddress,
        connectResponse: ConnectResponse,
        timestamp: Long
    ): Option[(ConnectSent, Tiers)] =
      current.possibleConnect(peer, connectResponse, timestamp).map {
        case (connectSent, newCurrentTier) => (connectSent, copy(current = newCurrentTier))
      }

    def possibleAnnounceResponse(peer: InetSocketAddress, announceResponse: AnnounceResponse): Option[Tiers] =
      current.possibleAnnounce(peer, announceResponse).map(newCurrentTier => copy(current = newCurrentTier))

    def currentState: (InetSocketAddress, State) = current.current
  }

  object Tiers {

    def fromSinglePeer(peer: InetSocketAddress, state: State): Tiers =
      Tiers(List.empty, CurrentTier((peer, state), List.empty), List.empty)

    def fromMultipleTiers(announceList: NonEmptyList[NonEmptyList[InetSocketAddress]], state: State): Tiers = {
      val `1stTier` = announceList.head
      val `1stPeer` = `1stTier`.head
      Tiers(List(), CurrentTier((`1stPeer`, state), `1stTier`.tail.map((_, false))), announceList.tail.map(Tier))
    }

  }

  private[tracker] case class Tier(trackers: NonEmptyList[InetSocketAddress]) extends AnyVal

  private[tracker] case class CurrentTier(
      current: (InetSocketAddress, State),
      others: List[(InetSocketAddress, Boolean)]
  ) {

    def possibleConnect(
        peer: InetSocketAddress,
        connectResponse: ConnectResponse,
        timestamp: Long
    ): Option[(ConnectSent, CurrentTier)] =
      if (peer == current._1)
        current._2 match {
          case a @ ConnectSent(txnId, _) if txnId == connectResponse.transactionId =>
            Some(a, copy(current = (peer, a.connectReceived(connectResponse, timestamp))))
          case _ => None
        }
      else None

    def possibleAnnounce(peer: InetSocketAddress, announceResponse: AnnounceResponse): Option[CurrentTier] =
      if (peer == current._1)
        current._2 match {
          case a @ AnnounceSent(txnId) if txnId == announceResponse.transactionId =>
            Some(copy(current = (peer, a.announceReceived(announceResponse))))
          case _ => None
        }
      else None
  }

  private[tracker] sealed trait State

  private[tracker] case class ConnectSent(txnId: Int, channel: Promise[Unit]) extends State {

    def connectReceived(connectResponse: ConnectResponse, timestamp: Long): ConnectReceived =
      ConnectReceived(connectResponse.connectionId, timestamp)
  }
  private[tracker] case class ConnectReceived(connectionId: Long, timestamp: Long) extends State

  private[tracker] case class AnnounceSent(txnId: Int) extends State {

    def announceReceived(announceResponse: AnnounceResponse): AnnounceReceived =
      AnnounceReceived(announceResponse.leechers, announceResponse.seeders, announceResponse.peers)
  }

  private[tracker] case class AnnounceReceived(leechers: Int, seeders: Int, peers: List[InetSocketAddress])
      extends State

}
