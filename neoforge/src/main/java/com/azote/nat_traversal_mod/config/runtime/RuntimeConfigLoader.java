package com.azote.nat_traversal_mod.config.runtime;

import com.azote.nat_traversal_mod.Config;

public final class RuntimeConfigLoader {
    private static final int QUIC_CLIENT_LOCAL_PORT_DEFAULT = 26668;

    private RuntimeConfigLoader() {
    }

    public static RuntimeConfigSnapshot load() {
        return new RuntimeConfigSnapshot(
                normalizeConnectStrategy(Config.modeConnectStrategy()),
                Config.modeInterceptHost(),
                Config.modeDebugForceLocalhost(),
                Config.modeRoomName(),
                Config.supabaseUrl(),
                Config.supabaseApiKey(),
                Config.publishHostName(),
                Config.publishHostIp(),
                Config.relayPublishEndpoint(),
                Config.relayConnectEndpointClient(),
                Config.relayConnectEndpointServer(),
                Config.relayToken(),
                Config.relayStatus(),
                Config.relayEnabled(),
                Config.relayLocalPort(),
                Config.stunEnabled(),
                Config.stunServer(),
                Config.stunTimeoutMs(),
                Config.quicEnabled(),
                Config.quicPublishEndpoint(),
                Config.quicStatus(),
                normalizeQuicTlsMode(Config.quicTlsModeName()),
                Config.quicTlsCertFile(),
                Config.quicTlsKeyFile(),
                Config.quicCertFingerprintSha256(),
                QUIC_CLIENT_LOCAL_PORT_DEFAULT,
                Config.quicAttempts(),
                Config.quicAttemptIntervalMs(),
                Config.routingTcpAttempts(),
                Config.routingStageResetMs()
        );
    }

    public static RuntimeConfigIssues collectIssues(RuntimeConfigSnapshot snapshot) {
        // Placeholder for stricter validation in next batch.
        return RuntimeConfigIssues.empty();
    }

    private static ConnectStrategy normalizeConnectStrategy(String strategy) {
        return switch (strategy) {
            case "tcp_only" -> ConnectStrategy.TCP_ONLY;
            case "quic_first" -> ConnectStrategy.QUIC_FIRST;
            case "relay_first" -> ConnectStrategy.RELAY_FIRST;
            case "tcp_quic_relay" -> ConnectStrategy.TCP_QUIC_RELAY;
            default -> ConnectStrategy.TCP_QUIC_RELAY;
        };
    }

    private static QuicTlsMode normalizeQuicTlsMode(String mode) {
        if ("insecure_trust_all".equals(mode)) {
            return QuicTlsMode.INSECURE_TRUST_ALL;
        }
        return QuicTlsMode.CA_OR_PINNED;
    }
}

