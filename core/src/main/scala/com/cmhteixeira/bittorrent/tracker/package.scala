package com.cmhteixeira.bittorrent

import scala.concurrent.Promise
package object tracker {

  private[tracker] sealed trait TrackerState

  private[tracker] object TrackerState {
    private[tracker] case class ConnectSent(txnId: Int, channel: Promise[(ConnectResponse, Long)]) extends TrackerState

    private[tracker] case class AnnounceSent(txnId: Int, connectionId: Long, channel: Promise[AnnounceResponse])
        extends TrackerState

    private[tracker] case class AnnounceReceived(timestamp: Long, numPeers: Int) extends TrackerState

  }

}
