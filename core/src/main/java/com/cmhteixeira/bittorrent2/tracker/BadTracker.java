package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.InfoHash;
import com.cmhteixeira.bittorrent.UdpSocket;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;

public class BadTracker implements TrackerJava {
  ImmutableMap<InetSocketAddress, DatagramSocket> sockets;
  final int packetSize = 65507;

  public BadTracker(ImmutableMap<InetSocketAddress, DatagramSocket> sockets) {
    this.sockets = sockets;
    Thread thread = new Thread(new ReadMessagesFromSockets(), "ProcessingThread");
    thread.start();
  }

  @Override
  public void submit(InfoHash torrent, ImmutableList<UdpSocket> trackers) {}

  @Override
  public ImmutableSet<InetSocketAddress> peers(InfoHash torrent) {
    return null;
  }

  private class ReadMessagesFromSockets implements Runnable {

    @Override
    public void run() {
      while (true) {
        for (Map.Entry<InetSocketAddress, DatagramSocket> tracker : sockets.entrySet()) {
          DatagramPacket packet = new DatagramPacket(new byte[packetSize], packetSize);
          InetSocketAddress trackerSocketAddress = tracker.getKey();
          DatagramSocket trackerSocket = tracker.getValue();
          try {
            trackerSocket.receive(packet);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
          //          process(packet);
        }
      }
    }
  }
}
