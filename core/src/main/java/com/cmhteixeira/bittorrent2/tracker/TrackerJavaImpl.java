package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.tracker.ConnectResponse;
import scala.NotImplementedError;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class TrackerJavaImpl implements TrackerJava {
    DatagramSocket socket;
    AtomicReference<java.util.Map<String, State>> sharedState;

    public TrackerJavaImpl(int port) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.sharedState = new AtomicReference<java.util.Map<String, State>>(Collections.emptyMap());
    }

    @Override
    public void submit(String torrent, List<InetSocketAddress> trackers) {
    }

    private CompletableFuture<Void> run(String torrent, InetSocketAddress tracker) {
        return connect(torrent, tracker)
                .thenComposeAsync(a -> announce(a, tracker, null), null)
                .thenComposeAsync(t -> setAndReannounce(torrent, tracker, null), null)
                .exceptionallyComposeAsync(error -> {
                    if (error instanceof TimeoutException) {
                        // We are going back ...
                        return run(torrent, tracker);
                    } else {
                        return CompletableFuture.failedFuture(error);
                    }
                }, null);
    }


//    private def run(infoHash: InfoHash, tracker: InetSocketAddress)(implicit ec: ExecutionContext): Future[Unit] =
//            (for {
//        (connectRes, timestampConn) <- connect(infoHash, tracker)
//        connection = Connection(connectRes.connectionId, timestampConn)
//        announceRes <- announce(infoHash, tracker, connection)
//        _ <- setAndReannounce(infoHash, tracker, announceRes.peers.toSet, connection)
//    } yield ()) recoverWith { case timeout: TimeoutException =>
//        logger.warn(s"Obtaining peers from '$tracker' from '$infoHash'. Retrying ...", timeout)
//        run(infoHash, tracker)
//    }

    CompletableFuture<String> connect(String torrent, InetSocketAddress tracker) {
        throw new NotImplementedError();
    }

    CompletableFuture<String> announce(String torrent, InetSocketAddress tracker, String connection) {
        throw new NotImplementedError();
    }

    CompletableFuture<Void> setAndReannounce(String torrent, InetSocketAddress tracker, Set<InetSocketAddress> peers) {
        throw new NotImplementedError();
    }

    @Override
    public Set<InetSocketAddress> peers(String torrent) {
        return Optional.of(sharedState.get().getOrDefault(torrent, null))
                .map(State::peers).orElseGet(Set::of);
    }

}
