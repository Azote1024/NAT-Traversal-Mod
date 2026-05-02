package com.azote.nat_traversal_mod.net;

import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;

import java.net.InetSocketAddress;
import java.util.Optional;

final class NoopQuicDirectConnector implements QuicDirectConnector {
    @Override
    public boolean isOperational() {
        return false;
    }

    @Override
    public Optional<ChannelFuture> connect(InetSocketAddress address, boolean useEpoll, Connection connection, String attemptId) {
        return Optional.empty();
    }
}


