package com.azote.nat_traversal_mod.net;

import java.net.InetSocketAddress;
import java.util.Optional;

public final class QuicDirectRouteContext {
    private static final ThreadLocal<RelayEndpoint> PENDING_ENDPOINT = new ThreadLocal<>();

    private QuicDirectRouteContext() {
    }

    public static void set(RelayEndpoint endpoint) {
        PENDING_ENDPOINT.set(endpoint);
    }

    public static Optional<RelayEndpoint> takeIfMatches(InetSocketAddress address) {
        RelayEndpoint endpoint = PENDING_ENDPOINT.get();
        PENDING_ENDPOINT.remove();
        if (endpoint == null) {
            return Optional.empty();
        }

        if (!endpoint.host().equalsIgnoreCase(address.getHostString()) || endpoint.port() != address.getPort()) {
            return Optional.empty();
        }
        return Optional.of(endpoint);
    }

    public static void clear() {
        PENDING_ENDPOINT.remove();
    }
}

