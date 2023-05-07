package com.cmhteixeira.bittorrent2.tracker;


import com.cmhteixeira.bittorrent.tracker.AnnounceResponse;
import com.cmhteixeira.bittorrent.tracker.ConnectResponse;
import org.javatuples.Pair;

import java.util.concurrent.CompletableFuture;

public interface TrackerState {
    record ConnectSent(int txdId, CompletableFuture<Pair<ConnectResponse, Long>> fut) implements TrackerState {
    }

    record ConnectReceived(long connectionId) implements TrackerState {
    }

    record AnnounceSent(int txnId, long connectionId, CompletableFuture<AnnounceResponse> fut) implements TrackerState {
    }

    record AnnounceReceived(long timestamp, int numPeers) implements TrackerState {
    }


}
