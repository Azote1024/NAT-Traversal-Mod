package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Nat_traversal_mod;

public final class QuicServerTunnelFactory {
    private QuicServerTunnelFactory() {
    }

    public static QuicServerTunnel create() {
        try {
            if (!QuicRuntimeClasspath.ensureAvailable()) {
                throw new IllegalStateException("QUIC runtime classes are unavailable");
            }
            Class<?> backendClass = Class.forName("com.azote.nat_traversal_mod.net.NettyQuicServerTunnel");
            Object backend = backendClass.getDeclaredConstructor().newInstance();
            if (backend instanceof QuicServerTunnel quicServerTunnel) {
                return quicServerTunnel;
            }
            throw new IllegalStateException("Unexpected backend type: " + backendClass.getName());
        } catch (Throwable throwable) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] QUIC server tunnel backend unavailable. Server-side QUIC disabled.",
                    throwable
            );
            return new NoopQuicServerTunnel();
        }
    }
}

