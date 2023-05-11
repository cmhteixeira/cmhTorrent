package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.InfoHash;
import com.cmhteixeira.bittorrent.PeerId;
import com.cmhteixeira.bittorrent.UdpSocket;
import com.cmhteixeira.bittorrent.tracker.*;
import com.google.common.collect.*;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.immutable.Map$;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class TrackerJavaImpl implements TrackerJava {
    DatagramSocket socket;
    AtomicReference<ImmutableMap<InfoHash, State>> sharedState;
    TransactionIdGenerator txdIdGen;
    Executor ex;
    Config config;
    Logger logger = LoggerFactory.getLogger("TrackerImpl");

    public TrackerJavaImpl(Config config, TransactionIdGenerator txdIdGen, Executor ex) throws SocketException {
        this.socket = new DatagramSocket(config.port);
        this.sharedState = new AtomicReference<ImmutableMap<InfoHash, State>>(ImmutableMap.of());
        this.txdIdGen = txdIdGen;
        this.ex = ex;
        this.config = config;
        Thread readerThread = new Thread(new TrackerReader(this.socket, this.sharedState), "ReaderThread");
        readerThread.start();
    }


    @Override
    public void submit(InfoHash torrent, ImmutableList<UdpSocket> trackers) {
        ImmutableMap<InfoHash, State> currentState = sharedState.get();
        ImmutableMap<InfoHash, State> newState = ImmutableMap.<InfoHash, State>builder().putAll(currentState).put(torrent, new State(ImmutableSet.of(), ImmutableMap.of())).build();
        if (!sharedState.compareAndSet(currentState, newState)) submit(torrent, trackers);
        else for (CompletableFuture<InetSocketAddress> fut : addTorrents(torrent, trackers)) {
            fut.thenApplyAsync(res -> {
                logger.info(String.format("Infinite loop for tracker '%s' on torrent '%s' finished.", res, torrent));
                return null;
            }).exceptionally(error -> {
                logger.error(String.format("Problem with a tracker for torrent '%s'.", torrent), error);
                return null;
            });
        }
    }

    private ImmutableList<CompletableFuture<InetSocketAddress>> addTorrents(InfoHash torrent, ImmutableList<UdpSocket> trackers) {
        return trackers.stream().map(udpSocket -> resolveHost(udpSocket).thenComposeAsync(inetSocketAddress -> run(torrent, inetSocketAddress).thenApply(a -> inetSocketAddress))).collect(ImmutableList.toImmutableList());
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

    private CompletableFuture<Void> run(InfoHash torrent, InetSocketAddress tracker) {
        return connect(torrent, tracker, 0).thenComposeAsync(connectResponseAndTimestamp -> {
            Connection connection = new Connection(connectResponseAndTimestamp.getValue0().connectionId(), connectResponseAndTimestamp.getValue1());
            return announce(torrent, tracker, connection, 0).thenComposeAsync(announceResponse -> setAndReannounce(torrent, tracker, announceResponse.peersJava(), connection), ex);
        }, ex).exceptionallyComposeAsync(error -> {
            if (error instanceof CompletionException) {
                Throwable cause = ((CompletionException) error).getCause();
                if (cause instanceof TimeoutException) {
                    logger.warn(String.format("Obtaining peers from '$%s' from '%s'. Retrying ...", tracker, torrent), cause);
                    return run(torrent, tracker);
                } else return CompletableFuture.failedFuture(cause);
            } else return CompletableFuture.failedFuture(error);
        });
    }

    CompletableFuture<Pair<ConnectResponse, Long>> connect(InfoHash torrent, InetSocketAddress tracker, int n) {
        ImmutableMap<InfoHash, State> currentState = sharedState.get();
        State state4Torrent = currentState.getOrDefault(torrent, null);
        if (state4Torrent == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(String.format("Connecting to %s but '%s' doesn't exist.", tracker, torrent)));
        } else {
            int txnId = txdIdGen.txnId();
            CompletableFuture<Pair<ConnectResponse, Long>> promise = new CompletableFuture<Pair<ConnectResponse, Long>>();
            State newState4Torrent = new State(state4Torrent.peers(), ImmutableMap.<InetSocketAddress, TrackerState>builder().putAll(state4Torrent.trackers().entrySet().stream().filter(a -> !a.getKey().equals(tracker)).collect(ImmutableList.toImmutableList())).put(tracker, new TrackerState.ConnectSent(txnId, promise)).build());

            ImmutableMap<InfoHash, State> newState = ImmutableMap.<InfoHash, State>builder().putAll(currentState.entrySet().stream().filter(a -> !Objects.equals(a.getKey(), torrent)).collect(ImmutableList.toImmutableList())).put(torrent, newState4Torrent).build();

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

    CompletableFuture<AnnounceResponse> announce(InfoHash torrent, InetSocketAddress tracker, Connection connection, int n) {
        ImmutableMap<InfoHash, State> currentState = sharedState.get();
        State state4Torrent = currentState.getOrDefault(torrent, null);
        if (state4Torrent == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(String.format("Connecting to %s but '%s' doesn't exist.", tracker, torrent)));
        } else {
            int txnId = txdIdGen.txnId();
            CompletableFuture<AnnounceResponse> promise = new CompletableFuture<AnnounceResponse>();
            State newState4Torrent = new State(state4Torrent.peers(), ImmutableMap.<InetSocketAddress, TrackerState>builder().putAll(state4Torrent.trackers().entrySet().stream().filter(a -> !a.getKey().equals(tracker)).collect(ImmutableList.toImmutableList())).put(tracker, new TrackerState.AnnounceSent(txnId, connection.connectionId(), promise)).build());

            ImmutableMap<InfoHash, State> newState = ImmutableMap.<InfoHash, State>builder().putAll(currentState.entrySet().stream().filter(a -> {
                return !Objects.equals(a.getKey(), torrent);
            }).collect(ImmutableList.toImmutableList())).put(torrent, newState4Torrent).build();

            if (!sharedState.compareAndSet(currentState, newState)) return announce(torrent, tracker, connection, n);
            else {
                sendAnnounceDownTheWire(torrent, connection.connectionId(), txnId, tracker);
                return promise.orTimeout(retries(Math.min(8, n)), TimeUnit.SECONDS).exceptionallyComposeAsync(error -> {
                    if (error instanceof TimeoutException) {
                        if (limitConnId(connection.timestamp)) {
                            return CompletableFuture.failedFuture(new TimeoutException(String.format("Connection to %s (%s s) expired before announce received.", tracker, connAgeSec(connection.timestamp))));
                        } else return announce(torrent, tracker, connection, n + 1);
                    } else return CompletableFuture.failedFuture(error);
                }, ex);
            }
        }
    }

    CompletableFuture<Void> setAndReannounce(InfoHash torrent, InetSocketAddress tracker, ImmutableSet<InetSocketAddress> peers, Connection connection) {
        ImmutableMap<InfoHash, State> currentState = sharedState.get();
        State state4Torrent = currentState.getOrDefault(torrent, null);
        if (state4Torrent == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(String.format("Connecting to %s but '%s' doesn't exist.", tracker, torrent)));
        } else {
            State newState4Torrent = new State(state4Torrent.peers(), ImmutableMap.<InetSocketAddress, TrackerState>builder().putAll(state4Torrent.trackers().entrySet().stream().filter(a -> !a.getKey().equals(tracker)).collect(ImmutableList.toImmutableList())).put(tracker, new TrackerState.AnnounceReceived(connection.timestamp, peers.size())).build());

            ImmutableMap<InfoHash, State> newState = ImmutableMap.<InfoHash, State>builder().putAll(currentState.entrySet().stream().filter(a -> {
                return !Objects.equals(a.getKey(), torrent);
            }).collect(ImmutableList.toImmutableList())).put(torrent, newState4Torrent).build();

            if (!sharedState.compareAndSet(currentState, newState))
                return setAndReannounce(torrent, tracker, peers, connection);
            else {
                Executor newEx = CompletableFuture.delayedExecutor(config.announceTimeInterval.toMillis(), TimeUnit.MILLISECONDS, ex);
                return CompletableFuture.completedFuture(null).thenComposeAsync(a -> {
                    if (limitConnId(connection.timestamp)) {
                        return CompletableFuture.failedFuture(new TimeoutException(String.format("Connection to %s (%s s) expired.", tracker, connAgeSec(connection.timestamp))));
                    } else {
                        logger.info(String.format("Re-announcing to '%s' for '%s' Previous peers obtained: %s", tracker, torrent, peers.size()));
                        return announce(torrent, tracker, connection, 0).thenComposeAsync(announceResponse -> setAndReannounce(torrent, tracker, announceResponse.peersJava(), connection), ex);
                    }
                }, newEx);
            }
        }
    }


    private boolean limitConnId(long timestamp) {
        return connAgeSec(timestamp) > 60;
    }

    private long connAgeSec(long timestamp) {
        return (System.nanoTime() - timestamp) / 1000000000L;
    }

    private void sendConnectDownTheWire(int txnId, InetSocketAddress tracker) {
        byte[] payload = new ConnectRequest(txnId).serialize();
        try {
            socket.send(new DatagramPacket(payload, payload.length, tracker));
            logger.info(String.format("Sent Connect to '%s' with transaction id '%s'.", tracker, txnId));
        } catch (Exception error) {
            logger.error(String.format("Sending connect message with txdId %s to %s", txnId, tracker), error);
        }
    }

    private void sendAnnounceDownTheWire(InfoHash infoHash, long conId, int txnId, InetSocketAddress tracker) {
        AnnounceRequest announceRequest = new AnnounceRequest(conId, com.cmhteixeira.bittorrent.tracker.AnnounceRequest.Announce$.MODULE$, txnId, infoHash, config.peerId, 0L, 0L, 0L, com.cmhteixeira.bittorrent.tracker.AnnounceRequest.None$.MODULE$, 20, // parametrized this
                config.key, 4, (short) 8081 // parametrize this
        );
        byte[] payload = announceRequest.serialize();
        try {
            socket.send(new DatagramPacket(payload, payload.length, tracker));
            logger.info(String.format("Sent Announce to %s' with transaction id '%s'.", tracker, txnId));
        } catch (Exception error) {
            logger.error(String.format("Sending announce message with txdId %s to %s", txnId, tracker), error);
        }
    }

    @Override
    public ImmutableSet<InetSocketAddress> peers(InfoHash torrent) {
        return ImmutableSet.of();
    }

    public ImmutableMap<InfoHash, Tracker.Statistics> statistics() {
        ImmutableMap.Builder<InfoHash, Tracker.Statistics> builder = new ImmutableMap.Builder<>();
        sharedState.get().forEach((key, value) -> builder.put(key, statistics(value)));

        return builder.build();
    }

    private Tracker.Statistics statistics(State state) {
        Tracker.Statistics stats = new Tracker.Statistics(new Tracker.Summary(0, 0, 0, 0, 0), Map$.MODULE$.empty());
        for (Map.Entry<InetSocketAddress, TrackerState> trackerToState : state.trackers().entrySet()) {
            InetSocketAddress tracker = trackerToState.getKey();
            switch (trackerToState.getValue()) {
                case TrackerState.ConnectSent connectSent -> stats = stats.addConnectSent(tracker);
                case TrackerState.AnnounceSent announceSent -> stats = stats.addAnnounceSent(tracker);
                case TrackerState.AnnounceReceived announceReceived ->
                        stats = stats.addAnnounceReceived(tracker, announceReceived.numPeers());
                case default -> throw new IllegalArgumentException("Never going to happen");
            }
        }
        stats = stats.setNumberPeers(state.peers().size());
        return stats;
    }

    private static record Connection(long connectionId, long timestamp) {
    }

    static public record Config(int port, PeerId peerId, int key, Duration announceTimeInterval) {
    }
}
