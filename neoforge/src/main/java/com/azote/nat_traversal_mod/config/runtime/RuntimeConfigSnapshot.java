package com.azote.nat_traversal_mod.config.runtime;

public record RuntimeConfigSnapshot(
        ConnectStrategy connectStrategy,
        String interceptHost,
        String roomName,
        String supabaseUrl,
        String supabaseApiKey,
        String publishHostName,
        String publishHostIp,
        String relayPublishEndpoint,
        String relayConnectEndpointClient,
        String relayConnectEndpointServer,
        String relayToken,
        String relayStatus,
        boolean relayClientConnectorEnabled,
        int relayClientLocalPort,
        boolean stunEnabled,
        String stunServer,
        int stunTimeoutMs,
        boolean quicEnabled,
        String quicPublishEndpoint,
        String quicStatus,
        QuicTlsMode quicTlsMode,
        String quicTlsCertFile,
        String quicTlsKeyFile,
        String quicCertFingerprintSha256,
        int quicClientLocalPort,
        int quicAttempts,
        int quicAttemptIntervalMs,
        int tcpAttempts,
        int routeStageResetMs
) {
}

