package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.tracker.AnnounceResponse;
import com.cmhteixeira.bittorrent.tracker.ConnectResponse;
import java.util.concurrent.CompletableFuture;
import org.javatuples.Pair;

public interface TrackerState {
    record ConnectSent(int txdId, CompletableFuture<Pair<ConnectResponse, Long>> fut) implements TrackerState {}

    record AnnounceSent(int txnId, long connectionId, CompletableFuture<AnnounceResponse> fut)
            implements TrackerState {}

    record AnnounceReceived(long timestamp, int numPeers) implements TrackerState {}
}
