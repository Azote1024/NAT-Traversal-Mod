package com.azote.nat_traversal_mod.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
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

        if (!isMatch(endpoint, address)) {
            return Optional.empty();
        }
        return Optional.of(endpoint);
    }

    private static boolean isMatch(RelayEndpoint endpoint, InetSocketAddress address) {
        if (endpoint.port() != address.getPort()) {
            return false;
        }

        String pendingHost = endpoint.host();
        String targetHost = address.getHostString();
        if (pendingHost.equalsIgnoreCase(targetHost)) {
            return true;
        }

        // Same-PC tests often mix localhost and loopback literals across resolver/connect phases.
        if (isLoopbackAlias(pendingHost) && isLoopbackAlias(targetHost)) {
            return true;
        }

        try {
            InetAddress pendingAddress = InetAddress.getByName(pendingHost);
            InetAddress targetAddress = InetAddress.getByName(targetHost);
            return pendingAddress.equals(targetAddress);
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    private static boolean isLoopbackAlias(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    public static void clear() {
        PENDING_ENDPOINT.remove();
    }
}

