package com.cmhteixeira.bittorrent2.tracker;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

public class TrackerJavaImpl implements TrackerJava{
    @Override
    public void submit(String torrent, List<InetSocketAddress> trackers) {

    }

    @Override
    public Set<InetSocketAddress> peers(String torrent) {
        return null;
    }
}
