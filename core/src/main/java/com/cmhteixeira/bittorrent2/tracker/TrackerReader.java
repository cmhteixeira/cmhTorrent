package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.InfoHash;
import com.cmhteixeira.bittorrent.tracker.AnnounceResponse;
import com.cmhteixeira.bittorrent.tracker.ConnectResponse;
import com.cmhteixeira.bittorrent.tracker.ConnectResponse$;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.util.Either;

final class TrackerReader implements Runnable {
  final DatagramSocket udpSocket;
  final AtomicReference<ImmutableMap<InfoHash, State>> theSharedState;
  final int packetSize = 65507;

  private final Logger logger = LoggerFactory.getLogger("TrackerReader");

  public TrackerReader(
      DatagramSocket udpSocket, AtomicReference<ImmutableMap<InfoHash, State>> sharedState) {
    this.udpSocket = udpSocket;
    this.theSharedState = sharedState;
    logger.info("Starting ...");
  }

  @Override
  public void run() {
    while (true) {
      DatagramPacket dg = new DatagramPacket(new byte[packetSize], packetSize);
      try {
        udpSocket.receive(dg);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      process(dg);
    }
  }

  private void process(DatagramPacket dg) {
    int payloadSize = dg.getLength();
    InetSocketAddress origin = (InetSocketAddress) dg.getSocketAddress();
    if (payloadSize == 16) {
      Either<String, ConnectResponse> a = ConnectResponse$.MODULE$.deserialize(dg.getData());
      if (a.isLeft()) {
        logger.error(String.format("Error deserializing: '%s'", a.left().get()));
      } else {
        ConnectResponse connectResponse = a.right().get();
        logger.info(
            String.format(
                "Received potential connect response from %s with txdId %s and connection id %s",
                origin, connectResponse.transactionId(), connectResponse.connectionId()));
        processConnect(origin, connectResponse, System.nanoTime());
      }
    } else if (payloadSize >= 20 && (payloadSize - 20) % 6 == 0) {
      Either<String, AnnounceResponse> a = AnnounceResponse.deserialize(dg.getData(), payloadSize);
      if (a.isLeft()) {
        logger.error(String.format("Error deserializing: '%s'", a.left().get()));
      } else {
        AnnounceResponse announceResponse = a.right().get();
        logger.info(
            String.format(
                "Received potential announce response from %s with txdId %s.",
                origin, announceResponse.transactionId()));
        processAnnounce(origin, announceResponse);
      }
    } else {
      logger.error("Not implemented.");
    }
  }

  private void processConnect(
      InetSocketAddress origin, ConnectResponse connectResponse, long timestamp) {
    ImmutableMap<InfoHash, State> currentState = theSharedState.get();
    ImmutableList<TrackerState.ConnectSent> thistha =
        currentState.entrySet().stream()
            .flatMap(
                entry -> {
                  State state4Torrent = entry.getValue();
                  return state4Torrent.trackers().entrySet().stream()
                      .flatMap(
                          t ->
                              switch (t.getValue()) {
                                case TrackerState.ConnectSent lk -> {
                                  if (lk.txdId() == connectResponse.transactionId()
                                      && t.getKey().equals(origin)) yield Stream.of(lk);
                                  else yield Stream.empty();
                                }
                                default -> Stream.empty();
                              });
                })
            .collect(ImmutableList.toImmutableList());

    int size = thistha.size();
    if (size == 1) {
      TrackerState.ConnectSent conSent = thistha.get(0);
      logger.info(
          String.format(
              "Matched Connect response: Tracker=%s,txdId=%s,connId=%s",
              origin, connectResponse.transactionId(), connectResponse.connectionId()));
      conSent.fut().complete(new Pair<>(connectResponse, timestamp));
    } else if (size == 0) {
      logger.info(
          String.format(
              "No trackers waiting connection for txdId %s. All trackers: ???.",
              connectResponse.transactionId()));
    } else {
      logger.info("Fooar ---");
    }
  }

  private void processAnnounce(InetSocketAddress origin, AnnounceResponse announceResponse) {
    ImmutableMap<InfoHash, State> currentState = theSharedState.get();
    ImmutableList<TrackerState.AnnounceSent> thistha =
        currentState.entrySet().stream()
            .flatMap(
                entry -> {
                  State state4Torrent = entry.getValue();
                  return state4Torrent.trackers().entrySet().stream()
                      .flatMap(
                          t ->
                              switch (t.getValue()) {
                                case TrackerState.AnnounceSent lk -> {
                                  if (lk.txnId() == announceResponse.transactionId()
                                      && t.getKey().equals(origin)) yield Stream.of(lk);
                                  else yield Stream.empty();
                                }
                                default -> Stream.empty();
                              });
                })
            .collect(ImmutableList.toImmutableList());

    int size = thistha.size();
    if (size == 1) {
      TrackerState.AnnounceSent announceSent = thistha.get(0);
      logger.info(
          String.format(
              "Matched Announce response: Tracker=%s,txdId=%s,connId=%s. Peers: %s",
              origin,
              announceResponse.transactionId(),
              announceSent.connectionId(),
              announceResponse.peers().size()));
      announceSent.fut().complete(announceResponse);
    } else if (size == 0) {
      logger.info("announce 0 elements");
    } else {
      logger.info("announce" + size + "elements.");
    }
  }
}
