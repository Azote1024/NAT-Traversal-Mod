package com.azote.nat_traversal_mod.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;

public final class QuicDirectRouteContext {
    private static final ThreadLocal<PendingRoute> PENDING_ROUTE = new ThreadLocal<>();

    public record PendingRoute(RelayEndpoint endpoint, String attemptId, InetSocketAddress fallbackTarget) {
    }

    private QuicDirectRouteContext() {
    }

    public static void set(RelayEndpoint endpoint, String attemptId) {
        set(endpoint, attemptId, null);
    }

    public static void set(RelayEndpoint endpoint, String attemptId, InetSocketAddress fallbackTarget) {
        String normalizedAttemptId = attemptId == null ? "" : attemptId;
        PENDING_ROUTE.set(new PendingRoute(endpoint, normalizedAttemptId, fallbackTarget));
    }

    public static Optional<PendingRoute> takeIfMatches(InetSocketAddress address) {
        PendingRoute route = PENDING_ROUTE.get();
        PENDING_ROUTE.remove();
        if (route == null) {
            return Optional.empty();
        }

        if (!isMatch(route.endpoint(), address)) {
            return Optional.empty();
        }
        return Optional.of(route);
    }

    public static Optional<String> currentAttemptId() {
        PendingRoute route = PENDING_ROUTE.get();
        if (route == null || route.attemptId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(route.attemptId());
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
        PENDING_ROUTE.remove();
    }
}

