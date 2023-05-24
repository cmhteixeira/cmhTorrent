package com.cmhteixeira.bittorrent2.tracker;

import com.cmhteixeira.bittorrent.InfoHash;
import com.cmhteixeira.bittorrent.UdpSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

public interface TrackerJava {
  public void submit(InfoHash torrent, List<UdpSocket> trackers);

  public Set<InetSocketAddress> peers(InfoHash torrent);
}
