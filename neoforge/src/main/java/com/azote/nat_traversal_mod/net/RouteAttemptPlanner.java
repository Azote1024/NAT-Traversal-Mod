package com.azote.nat_traversal_mod.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RouteAttemptPlanner {
    public enum Decision {
        TCP_DIRECT,
        QUIC_DIRECT,
        RELAY
    }

    private static final Map<String, MutableState> STATES = new ConcurrentHashMap<>();

    private RouteAttemptPlanner() {
    }

    public static Decision planForConnector(String roomName, int tcpMaxAttempts, int quicMaxAttempts, long resetTtlMs) {
        long now = System.currentTimeMillis();
        MutableState state = STATES.computeIfAbsent(roomName, ignored -> new MutableState());
        synchronized (state) {
            if (resetTtlMs > 0 && now - state.lastUpdatedMs > resetTtlMs) {
                state.tcpAttemptsUsed = 0;
                state.quicAttemptsUsed = 0;
            }

            state.lastUpdatedMs = now;
            if (state.tcpAttemptsUsed < Math.max(0, tcpMaxAttempts)) {
                state.tcpAttemptsUsed++;
                return Decision.TCP_DIRECT;
            }
            if (state.quicAttemptsUsed < Math.max(0, quicMaxAttempts)) {
                state.quicAttemptsUsed++;
                return Decision.QUIC_DIRECT;
            }
            return Decision.RELAY;
        }
    }

    private static final class MutableState {
        private int tcpAttemptsUsed;
        private int quicAttemptsUsed;
        private long lastUpdatedMs;
    }
}

