package com.cmhteixeira.bittorrent

import cats.data.NonEmptyList

import java.net.InetSocketAddress
import scala.concurrent.Promise

package object tracker {

  private[tracker] sealed trait Foo

  private[tracker] case object Submitted extends Foo

  private[tracker] case class Tiers[+A <: State](underlying: NonEmptyList[(InetSocketAddress, A)]) extends Foo {
    def toList: List[(InetSocketAddress, A)] = underlying.toList

    def updateEntry(tracker: InetSocketAddress, newState: State): Tiers[State] =
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
          case (thisTrackerSocket, a @ ConnectSent(thisTxnId, _, _))
              if thisTrackerSocket == trackerSocket && thisTxnId == connectResponse.transactionId =>
            a
        }

    def announceResponse(
        trackerSocket: InetSocketAddress,
        announceResponse: AnnounceResponse
    ): Option[AnnounceSent] =
      toList.collectFirst {
        case (thisTrackerSocket, a @ AnnounceSent(txnId, _, _, _))
            if thisTrackerSocket == trackerSocket && txnId == announceResponse.transactionId =>
          a
      }
  }

  private[tracker] object Tiers {

    def start(
        txnIdGenerator: TransactionIdGenerator,
        torrent: Torrent
    ): NonEmptyList[(UdpSocket, ConnectSent)] =
      torrent.announceList.fold(
        NonEmptyList.one(torrent.announce -> ConnectSent(txnIdGenerator.newTransactionId(), Promise[Unit](), 0))
      )(announceList =>
        announceList.flatMap(
          _.map(trackerSocket => trackerSocket -> ConnectSent(txnIdGenerator.newTransactionId(), Promise[Unit](), 0))
        )
      )

  }

  private[tracker] sealed trait State

  private[tracker] case class ConnectSent(txnId: Int, channel: Promise[Unit], n: Int) extends State

  private[tracker] case class ConnectReceived(connectionId: Long, timestamp: Long) extends State

  private[tracker] case class AnnounceSent(txnId: Int, connectionId: Long, timestampConnectionId: Long, n: Int)
      extends State

  private[tracker] case class AnnounceReceived(leechers: Int, seeders: Int, peers: List[InetSocketAddress])
      extends State

}
