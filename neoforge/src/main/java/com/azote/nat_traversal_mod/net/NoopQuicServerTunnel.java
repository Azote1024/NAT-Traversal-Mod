package com.azote.nat_traversal_mod.net;

final class NoopQuicServerTunnel implements QuicServerTunnel {
    @Override
    public void start(int serverPort) {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isRunning() {
        return false;
    }
}


