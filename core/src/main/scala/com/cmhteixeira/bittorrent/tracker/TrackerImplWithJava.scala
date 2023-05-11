package com.cmhteixeira.bittorrent.tracker

import cats.implicits.catsSyntaxFlatten
import com.cmhteixeira.bittorrent
import com.cmhteixeira.bittorrent.UdpSocket
import com.cmhteixeira.bittorrent2.tracker.TrackerJavaImpl
import com.google.common.collect.ImmutableList

import java.net.InetSocketAddress
import java.util.concurrent.Executor
import scala.jdk.CollectionConverters.{IterableHasAsJava, IterableHasAsScala}

class TrackerImplWithJava private (trackerJavaImpl: TrackerJavaImpl) extends Tracker {
  override def peers(
      infoHash: bittorrent.InfoHash
  ): Set[InetSocketAddress] = ???
  override def statistics: Map[bittorrent.InfoHash, Tracker.Statistics] =
    trackerJavaImpl.statistics().entrySet().stream().toList.asScala.map(a => (a.getKey, a.getValue)).toMap
  override def submit(torrent: Torrent): Unit = {
    val udpSockets = torrent.announce +: torrent.announceList.fold[List[UdpSocket]](List.empty)(a => a.flatten.toList)
    trackerJavaImpl.submit(torrent.infoHash, new ImmutableList.Builder().addAll(udpSockets.asJava).build())
  }

}

object TrackerImplWithJava {
  def apply(trackerJavaImpl: TrackerJavaImpl): TrackerImplWithJava = new TrackerImplWithJava(trackerJavaImpl)

  def apply(config: TrackerJavaImpl.Config, ex: Executor, txdIdGenerator: TransactionIdGenerator): TrackerImplWithJava =
    new TrackerImplWithJava(new TrackerJavaImpl(config, txdIdGenerator, ex))
}
