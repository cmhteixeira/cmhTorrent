package com.cmhteixeira.bittorrent.tracker
import com.cmhteixeira.bittorrent.InfoHash

import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}
import java.nio.ByteBuffer

class BadTracker private(sockets: Map[InetSocketAddress, DatagramSocket]) extends Tracker {
  override def peers(infoHash: InfoHash): Set[InetSocketAddress] = ???
  override def statistics: Map[InfoHash, Tracker.Statistics] =
    ???
  override def submit(torrent: Torrent): Unit = ???
}

object BadTracker {
  private val maximumUdpPacketSize = 65507

  private class ReadMessagesFromSockets(sockets: Map[InetSocketAddress, DatagramSocket]) extends Runnable {
    override def run(): Unit = {
      sockets.foreach { case (socketAddress, socket) =>
        val packet = new DatagramPacket(ByteBuffer.allocate(maximumUdpPacketSize).array(), maximumUdpPacketSize)
        socket.receive(packet)
        processPacket(packet)
      }
      run()
    }
    private def processPacket(packet: DatagramPacket) = ???
  }

  def apply(sockets: Map[InetSocketAddress, DatagramSocket]): BadTracker = {
    val thread = new Thread(new ReadMessagesFromSockets(sockets), "ProcessingThread")
    thread.start()
    new BadTracker(sockets)
  }
}
