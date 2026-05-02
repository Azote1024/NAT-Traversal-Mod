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

    public record PlanResult(
            Decision decision,
            int tcpAttemptsUsed,
            int tcpMaxAttempts,
            int quicAttemptsUsed,
            int quicMaxAttempts
    ) {
    }

    private RouteAttemptPlanner() {
    }

    public static Decision planForConnector(String roomName, int tcpMaxAttempts, int quicMaxAttempts, long resetTtlMs) {
        return planForConnectorDetailed(roomName, tcpMaxAttempts, quicMaxAttempts, resetTtlMs).decision();
    }

    public static PlanResult planForConnectorDetailed(String roomName, int tcpMaxAttempts, int quicMaxAttempts, long resetTtlMs) {
        long now = System.currentTimeMillis();
        int normalizedTcpMax = Math.max(0, tcpMaxAttempts);
        int normalizedQuicMax = Math.max(0, quicMaxAttempts);
        MutableState state = STATES.computeIfAbsent(roomName, ignored -> new MutableState());
        synchronized (state) {
            if (resetTtlMs > 0 && now - state.lastUpdatedMs > resetTtlMs) {
                state.tcpAttemptsUsed = 0;
                state.quicAttemptsUsed = 0;
            }

            state.lastUpdatedMs = now;
            if (state.tcpAttemptsUsed < normalizedTcpMax) {
                state.tcpAttemptsUsed++;
                return new PlanResult(
                        Decision.TCP_DIRECT,
                        state.tcpAttemptsUsed,
                        normalizedTcpMax,
                        state.quicAttemptsUsed,
                        normalizedQuicMax
                );
            }
            if (state.quicAttemptsUsed < normalizedQuicMax) {
                state.quicAttemptsUsed++;
                return new PlanResult(
                        Decision.QUIC_DIRECT,
                        state.tcpAttemptsUsed,
                        normalizedTcpMax,
                        state.quicAttemptsUsed,
                        normalizedQuicMax
                );
            }
            return new PlanResult(
                    Decision.RELAY,
                    state.tcpAttemptsUsed,
                    normalizedTcpMax,
                    state.quicAttemptsUsed,
                    normalizedQuicMax
            );
        }
    }

    private static final class MutableState {
        private int tcpAttemptsUsed;
        private int quicAttemptsUsed;
        private long lastUpdatedMs;
    }
}

