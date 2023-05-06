package com.cmhteixeira.bittorrent2.tracker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class TrackerReader implements Runnable {
    DatagramSocket udpSocket;
    AtomicReference<Map<String, State>> theSharedState;

    int packetSize = 65507;

    public TrackerReader(DatagramSocket udpSocket, AtomicReference<java.util.Map<String, State>> sharedState) {
        this.udpSocket = udpSocket;
        theSharedState = sharedState;
    }

    @Override
    public void run() {
        while (true) {
            DatagramPacket dg = new DatagramPacket(new byte[packetSize], packetSize);
            try {
                udpSocket.receive(dg);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            process(dg);
        }
    }

    private void process(DatagramPacket packet) {

    }
}
