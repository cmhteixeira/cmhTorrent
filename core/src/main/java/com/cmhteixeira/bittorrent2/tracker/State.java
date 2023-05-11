package com.cmhteixeira.bittorrent2.tracker;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.net.InetSocketAddress;

public record State(ImmutableSet<InetSocketAddress> peers, ImmutableMap<InetSocketAddress, TrackerState> trackers) {}
