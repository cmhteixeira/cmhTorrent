package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.InfoHash;
import com.cmhteixeira.bittorrent.tracker.AnnounceResponse;
import com.cmhteixeira.bittorrent.tracker.ConnectResponse;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
      try {
        ConnectResponse connectResponse = ConnectResponse.deserializeJava(dg.getData());
        logger.info(
            String.format(
                "Received potential connect response from %s with txdId %s and connection id %s",
                origin, connectResponse.transactionId(), connectResponse.connectionId()));
        processConnect(origin, connectResponse, System.nanoTime());
      } catch (Error e) {
        logger.error(
            String.format("Deserializing to connect response from '%s'", dg.getSocketAddress()), e);
      }
    } else if (payloadSize >= 20 && (payloadSize - 20) % 6 == 0) {
      try {
        AnnounceResponse announceResponse =
            AnnounceResponse.deserializeJava(dg.getData(), payloadSize);
        logger.info(
            String.format(
                "Potential announce response from %s with txdId %s.",
                origin, announceResponse.transactionId()));
        processAnnounce(origin, announceResponse);
      } catch (Error e) {
        logger.error(
            String.format("Deserializing to announce response from %s", dg.getSocketAddress()), e);
      }
    } else {
      logger.error("Not implemented.");
    }
  }

  private void processConnect(
      InetSocketAddress origin, ConnectResponse connectResponse, long timestamp) {
    ImmutableMap<InfoHash, State> currentState = theSharedState.get();
    Optional<TrackerState.ConnectSent> connectSentOptional =
        currentState.entrySet().stream()
            .flatMap(
                entryPerTorrent ->
                    entryPerTorrent.getValue().trackers().entrySet().stream()
                        .flatMap(
                            entryPerTracker ->
                                switch (entryPerTracker.getValue()) {
                                  case TrackerState.ConnectSent connectSent -> {
                                    if (connectSent.txdId() == connectResponse.transactionId()
                                        && entryPerTracker.getKey().equals(origin))
                                      yield Stream.of(connectSent);
                                    else yield Stream.empty();
                                  }
                                  default -> Stream.empty();
                                }))
            .findFirst();

    if (connectSentOptional.isPresent()) {
      TrackerState.ConnectSent connectSent = connectSentOptional.get();
      logger.info(
          String.format(
              "Matched Connect response: Tracker=%s,txdId=%s,connId=%s",
              origin, connectResponse.transactionId(), connectResponse.connectionId()));
      connectSent.fut().complete(new Pair<>(connectResponse, timestamp));
    } else
      logger.info(
          String.format(
              "No trackers waiting connection for txdId %s. All trackers: ???.",
              connectResponse.transactionId()));
  }

  private void processAnnounce(InetSocketAddress origin, AnnounceResponse announceResponse) {
    ImmutableMap<InfoHash, State> currentState = theSharedState.get();

    Optional<TrackerState.AnnounceSent> announceSentOptional =
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
            .findFirst();

    if (announceSentOptional.isPresent()) {
      TrackerState.AnnounceSent announceSent = announceSentOptional.get();
      logger.info(
          String.format(
              "Matched Announce response: Tracker=%s,txdId=%s,connId=%s. Peers: %s",
              origin,
              announceResponse.transactionId(),
              announceSent.connectionId(),
              announceResponse.peers().size()));
      announceSent.fut().complete(announceResponse);
    } else logger.info("announce 0 elements");
  }
}
