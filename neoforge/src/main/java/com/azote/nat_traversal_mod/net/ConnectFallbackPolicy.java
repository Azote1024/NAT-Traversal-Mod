package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.net.routing.QuicDirectRouteContext;

import java.net.InetSocketAddress;
import java.util.Optional;

public final class ConnectFallbackPolicy {
    public enum Route {
        RELAY,
        ORIGINAL_TCP,
        NONE
    }

    public record Decision(Route route, InetSocketAddress target) {
        static Decision none() {
            return new Decision(Route.NONE, null);
        }
    }

    private ConnectFallbackPolicy() {
    }

    public static Decision decide(QuicDirectRouteContext.PendingRoute pendingRoute) {
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        Optional<InetSocketAddress> relayTarget = resolveRelayTarget(runtimeConfig);
        if (relayTarget.isPresent()) {
            return new Decision(Route.RELAY, relayTarget.get());
        }

        Optional<InetSocketAddress> fallbackTarget = resolveOriginalTcpTarget(pendingRoute);
        if (fallbackTarget.isPresent()) {
            return new Decision(Route.ORIGINAL_TCP, fallbackTarget.get());
        }

        return Decision.none();
    }

    private static Optional<InetSocketAddress> resolveRelayTarget(RuntimeConfigSnapshot runtimeConfig) {
        if (!RelayClientConnectorManager.ensureStarted()) {
            return Optional.empty();
        }
        if (!RelayClientConnectorManager.isRelayUpstreamReachable(500)) {
            return Optional.empty();
        }
        return Optional.of(new InetSocketAddress("127.0.0.1", runtimeConfig.relayClientLocalPort()));
    }

    private static Optional<InetSocketAddress> resolveOriginalTcpTarget(QuicDirectRouteContext.PendingRoute pendingRoute) {
        InetSocketAddress fallbackTarget = pendingRoute.fallbackTarget();
        if (fallbackTarget == null) {
            return Optional.empty();
        }
        if (fallbackTarget.getPort() < 1 || fallbackTarget.getPort() > 65535) {
            return Optional.empty();
        }
        return Optional.of(fallbackTarget);
    }
}

