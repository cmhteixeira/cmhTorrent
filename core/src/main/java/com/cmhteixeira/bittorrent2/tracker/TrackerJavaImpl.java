package com.cmhteixeira.bittorrent2.tracker;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class TrackerJavaImpl implements TrackerJava {
    DatagramSocket socket;
    AtomicReference<java.util.Map<String, State>> sharedState;

    public TrackerJavaImpl(int port) throws SocketException {
        this.socket = new DatagramSocket(port);
        sharedState = new AtomicReference<java.util.Map<String, State>>(Collections.emptyMap());
    }

    @Override
    public void submit(String torrent, List<InetSocketAddress> trackers) {
    }

    @Override
    public Set<InetSocketAddress> peers(String torrent) {
        return null;
    }

}
