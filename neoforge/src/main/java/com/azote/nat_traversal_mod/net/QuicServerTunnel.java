package com.azote.nat_traversal_mod.net;

public interface QuicServerTunnel {
    void start(int serverPort);

    void stop();

    boolean isRunning();
}

