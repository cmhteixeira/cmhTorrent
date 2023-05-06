package com.cmhteixeira.bittorrent2.tracker;

public interface TrackerState {
    record ConnectSent(int txdId) implements TrackerState {
    }

    record ConnectReceived(long connectionId) implements TrackerState {
    }

    record AnnounceSent(int txnId, long connectionId) implements TrackerState {
    }

    record AnnounceReceived(long timestamp, int numPeers) implements TrackerState {
    }

}
