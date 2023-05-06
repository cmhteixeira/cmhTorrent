package com.cmhteixeira.bittorrent2.tracker;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Set;

public record State(Set<InetSocketAddress> peers, HashMap<InetSocketAddress, TrackerState> trackers) {
}

