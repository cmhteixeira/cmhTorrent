package com.cmhteixeira.bittorrent2.tracker;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

public interface TrackerJava {
    public void submit(String torrent, List<InetSocketAddress> trackers);

    public Set<InetSocketAddress> peers(String torrent);
}
