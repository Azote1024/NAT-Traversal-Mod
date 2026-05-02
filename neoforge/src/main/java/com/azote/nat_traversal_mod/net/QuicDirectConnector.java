package com.azote.nat_traversal_mod.net;

import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;

import java.net.InetSocketAddress;
import java.util.Optional;

interface QuicDirectConnector {
    boolean isOperational();

    Optional<ChannelFuture> connect(InetSocketAddress address, boolean useEpoll, Connection connection);
}

