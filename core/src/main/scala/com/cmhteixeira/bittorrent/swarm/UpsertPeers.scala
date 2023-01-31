package com.cmhteixeira.bittorrent.swarm

import com.cmhteixeira.bittorrent.tracker.Tracker
import org.slf4j.LoggerFactory
import State._
import com.cmhteixeira.bittorrent.swarm.SwarmImpl.PeerFactory

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

private[swarm] class UpsertPeers private (
    peers: AtomicReference[Map[InetSocketAddress, PeerState]],
    peerFactory: PeerFactory,
    tracker: Tracker,
    torrent: Torrent
) extends Runnable {
  private val logger = LoggerFactory.getLogger("Swarm")

  def run(): Unit = {
    val currentPeers = peers.get()
    val newPeers = tracker
      .peers(torrent.infoHash)
      .filterNot(currentPeers.contains)
      .map(peerSocket => peerSocket -> Active(peerFactory(peerSocket)))
      .toMap

    if (!peers.compareAndSet(currentPeers, currentPeers ++ newPeers)) run()
    else {
      logger.info(s"Added ${newPeers.size} new peer(s). Initializing each ...")
      newPeers.foreach { case (_, peer) => peer.peer.start() }
    }
  }
}

private[swarm] object UpsertPeers {

  def apply(
      peers: AtomicReference[Map[InetSocketAddress, PeerState]],
      peerFactory: PeerFactory,
      torrent: Torrent,
      tracker: Tracker
  ): UpsertPeers = new UpsertPeers(peers, peerFactory, tracker, torrent)
}
