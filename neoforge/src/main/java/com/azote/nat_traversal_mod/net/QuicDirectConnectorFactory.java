package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Nat_traversal_mod;

import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;

import java.net.InetSocketAddress;
import java.util.Optional;

public final class QuicDirectConnectorFactory {
    private static final QuicDirectConnector CONNECTOR = createInternal();

    private QuicDirectConnectorFactory() {
    }

    public static boolean isOperational() {
        return CONNECTOR.isOperational();
    }

    public static Optional<ChannelFuture> connect(InetSocketAddress address, boolean useEpoll, Connection connection, String attemptId) {
        return CONNECTOR.connect(address, useEpoll, connection, attemptId);
    }

    private static QuicDirectConnector createInternal() {
        try {
            if (!QuicRuntimeClasspath.ensureAvailable()) {
                throw new IllegalStateException("QUIC runtime classes are unavailable");
            }
            Class<?> connectorClass = Class.forName("com.azote.nat_traversal_mod.net.NettyQuicDirectConnector");
            Object connector = connectorClass.getDeclaredConstructor().newInstance();
            if (connector instanceof QuicDirectConnector quicDirectConnector) {
                return quicDirectConnector;
            }
            throw new IllegalStateException("Unexpected connector type: " + connectorClass.getName());
        } catch (Throwable throwable) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] QUIC direct connector backend unavailable. Direct QUIC client connect disabled.",
                    throwable
            );
            return new NoopQuicDirectConnector();
        }
    }
}

