package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.UdpSocket;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.net.InetSocketAddress;


public interface TrackerJava {
    public void submit(String torrent, ImmutableList<UdpSocket> trackers);

    public ImmutableSet<InetSocketAddress> peers(String torrent);
}
