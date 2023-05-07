package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.UdpSocket;
import com.cmhteixeira.bittorrent.tracker.*;
import com.google.common.collect.*;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.NotImplementedError;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class TrackerJavaImpl implements TrackerJava {
    DatagramSocket socket;
    AtomicReference<ImmutableMap<String, State>> sharedState;
    TransactionIdGenerator txdIdGen;

    Executor ex;
    Logger logger = LoggerFactory.getLogger("TrackerImpl");

    public TrackerJavaImpl(int port, TransactionIdGenerator txdIdGen, Executor ex) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.sharedState = new AtomicReference<ImmutableMap<String, State>>(ImmutableMap.of());
        this.txdIdGen = txdIdGen;
        this.ex = ex;
        Thread readerThread = new Thread(new TrackerReader(this.socket, this.sharedState), "ReaderThread");
        readerThread.start();
        logger.info("Starting ....");
    }


    @Override
    public void submit(String torrent, ImmutableList<UdpSocket> trackers) {
        logger.info("Submitting ....");
        ImmutableMap<String, State> currentState = sharedState.get();
        ImmutableMap<String, State> newState = ImmutableMap.<String, State>builder().putAll(currentState).put(torrent, new State(ImmutableSet.of(), ImmutableMap.of())).build();
        if (!sharedState.compareAndSet(currentState, newState)) submit(torrent, trackers);
        else
            for (CompletableFuture<InetSocketAddress> fut : addTorrents(torrent, trackers)) {
                fut.thenApplyAsync(res -> {
                    logger.info(String.format("Received connect response for '%s'.", res));
                    return null;
                }).exceptionally(error -> {
                    logger.error("Error", error);
                    return null;
                });
            }
    }

    private ImmutableList<CompletableFuture<InetSocketAddress>> addTorrents(String torrent, ImmutableList<UdpSocket> trackers) {
        return trackers.stream().map(udpSocket -> {
            return resolveHost(udpSocket)
                    .thenComposeAsync(inetSocketAddress -> run(torrent, inetSocketAddress).thenApply(a -> inetSocketAddress));
        }).collect(ImmutableList.toImmutableList());
    }

    private CompletableFuture<InetSocketAddress> resolveHost(UdpSocket tracker) {
        logger.info(String.format("Resolving %s", tracker));
        return CompletableFuture.completedFuture(tracker).thenComposeAsync(a -> {
            try {
                InetSocketAddress socketAddress = new InetSocketAddress(a.hostName(), a.port());
                if (socketAddress.isUnresolved()) {
                    logger.error(String.format("Tracker %s could not be resolved.", tracker));
                    return CompletableFuture.failedFuture(new IllegalArgumentException(String.format("Tracker '%s:%s' couldn't be resolved.", tracker.hostName(), tracker.port())));
                } else {
                    logger.info(String.format("Resolved successfully %s", tracker));
                    return CompletableFuture.completedFuture(socketAddress);
                }
            } catch (Exception error) {
                logger.error(String.format("Error resolving %s", tracker), error);
                return CompletableFuture.failedFuture(new IllegalArgumentException(String.format("Tracker '%s:%s' couldn't be resolved.", tracker.hostName(), tracker.port()), error));
            }
        }, ex);
    }

    private CompletableFuture<AnnounceResponse> run(String torrent, InetSocketAddress tracker) {
        return connect(torrent, tracker, 0).thenComposeAsync(connectResponseAndTimestamp -> {
            return announce(torrent, tracker, connectResponseAndTimestamp, 0);
        });
    }

    CompletableFuture<Pair<ConnectResponse, Long>> connect(String torrent, InetSocketAddress tracker, int n) {
        ImmutableMap<String, State> currentState = sharedState.get();
        State state4Torrent = currentState.getOrDefault(torrent, null);
        if (state4Torrent == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(String.format("Connecting to %s but '%s' doesn't exist.", tracker, torrent)));
        } else {
            int txnId = txdIdGen.txnId();
            CompletableFuture<Pair<ConnectResponse, Long>> promise = new CompletableFuture<Pair<ConnectResponse, Long>>();
            State newState4Torrent = new State(state4Torrent.peers(), ImmutableMap.<InetSocketAddress, TrackerState>builder().putAll(
                    state4Torrent.trackers().entrySet().stream().filter(a -> {
                        return !a.getKey().equals(tracker);
                    }).collect(ImmutableList.toImmutableList())).put(tracker, new TrackerState.ConnectSent(txnId, promise)).build());

            ImmutableMap<String, State> newState = ImmutableMap.<String, State>builder().putAll(
                    currentState.entrySet().stream().filter(a -> {
                        return !Objects.equals(a.getKey(), torrent);
                    }).collect(ImmutableList.toImmutableList())).put(torrent, newState4Torrent).build();

            if (!sharedState.compareAndSet(currentState, newState)) return connect(torrent, tracker, n);
            else {
                sendConnectDownTheWire(txnId, tracker);
                return promise.orTimeout(retries(Math.min(8, n)), TimeUnit.SECONDS).exceptionallyComposeAsync(error -> {
                    if (error instanceof TimeoutException) return connect(torrent, tracker, n + 1);
                    else return CompletableFuture.failedFuture(error);
                }, ex);
            }
        }
    }

    private static int retries(int n) {
        return (int) (15 * (Math.pow(2, n)));
    }

    CompletableFuture<AnnounceResponse> announce(String torrent, InetSocketAddress tracker, Pair<ConnectResponse, Long> connectionAndTimestamp, int n) {
        ImmutableMap<String, State> currentState = sharedState.get();
        State state4Torrent = currentState.getOrDefault(torrent, null);
        if (state4Torrent == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(String.format("Connecting to %s but '%s' doesn't exist.", tracker, torrent)));
        } else {
            int txnId = txdIdGen.txnId();
            CompletableFuture<AnnounceResponse> promise = new CompletableFuture<AnnounceResponse>();
            State newState4Torrent = new State(state4Torrent.peers(), ImmutableMap.<InetSocketAddress, TrackerState>builder().putAll(
                    state4Torrent
                            .trackers()
                            .entrySet()
                            .stream()
                            .filter(a -> !a.getKey().equals(tracker))
                            .collect(ImmutableList.toImmutableList())
            ).put(tracker, new TrackerState.AnnounceSent(txnId, connectionAndTimestamp.getValue0().connectionId(), promise)).build());

            ImmutableMap<String, State> newState = ImmutableMap.<String, State>builder().putAll(
                    currentState.entrySet().stream().filter(a -> {
                        return !Objects.equals(a.getKey(), torrent);
                    }).collect(ImmutableList.toImmutableList())).put(torrent, newState4Torrent).build();

            if (!sharedState.compareAndSet(currentState, newState))
                return announce(torrent, tracker, connectionAndTimestamp, n);
            else {
                sendAnnounceDownTheWire(torrent, connectionAndTimestamp.getValue0().connectionId(), txnId, tracker);
                return promise.orTimeout(retries(Math.min(8, n)), TimeUnit.SECONDS).exceptionallyComposeAsync(error -> {
                    if (error instanceof TimeoutException)
                        return announce(torrent, tracker, connectionAndTimestamp, n + 1);
                    else return CompletableFuture.failedFuture(error);
                }, ex);
            }
        }
    }

    CompletableFuture<Void> setAndReannounce(String torrent, InetSocketAddress tracker, Set<InetSocketAddress> peers) {
        throw new NotImplementedError();
    }

    private void sendConnectDownTheWire(int txnId, InetSocketAddress tracker) {
        byte[] payload = new ConnectRequest(txnId).serialize();
        try {
            socket.send(new DatagramPacket(payload, payload.length, tracker));
            logger.info(String.format("Sent Connect to %s' with transaction id '%s'.", txnId, tracker));
        } catch (Exception error) {
            logger.error(String.format("Sending connect message with txdId %s to %s", txnId, tracker), error);
        }
    }

    private void sendAnnounceDownTheWire(String infoHash, long conId, int txnId, InetSocketAddress tracker) {
        AnnounceRequest announceRequest = new AnnounceRequest(
                conId,
                com.cmhteixeira.bittorrent.tracker.AnnounceRequest.Announce$.MODULE$,
                txnId,
                null,
                null,
                0L,
                0L,
                0L,
                com.cmhteixeira.bittorrent.tracker.AnnounceRequest.None$.MODULE$,
                20, // parametrized this
                0,
                4,
                (short) 8081 // parametrize this
        );
        byte[] payload = announceRequest.serialize();
        try {
            socket.send(new DatagramPacket(payload, payload.length, tracker));
        } catch (Exception ignored) {

        }
    }

    @Override
    public ImmutableSet<InetSocketAddress> peers(String torrent) {
        return ImmutableSet.of();
    }


}
