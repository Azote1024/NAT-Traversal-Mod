package com.azote.nat_traversal_mod.net.supabase;

import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;

record PublishConfigSnapshot(
        String supabaseUrl,
        String supabaseKey,
        String roomName,
        String hostName,
        String hostIp,
        String relayEndpoint,
        String relayToken,
        String relayStatus,
        String quicBindHost,
        int quicBindPort,
        String quicStatus,
        boolean stunEnabled,
        String stunServer,
        int stunTimeoutMs,
        int quicAttempts,
        int quicAttemptIntervalMs
) {
    static PublishConfigSnapshot capture() {
        RuntimeConfigSnapshot runtime = RuntimeConfigLoader.load();
        return new PublishConfigSnapshot(
                runtime.supabaseUrl(),
                runtime.supabaseApiKey(),
                runtime.roomName(),
                runtime.publishHostName(),
                runtime.publishHostIp(),
                runtime.relayPublishEndpoint(),
                runtime.relayToken(),
                runtime.relayStatus(),
                runtime.quicBindHost(),
                runtime.quicBindPort(),
                runtime.quicStatus(),
                runtime.stunEnabled(),
                runtime.stunServer(),
                runtime.stunTimeoutMs(),
                runtime.quicAttempts(),
                runtime.quicAttemptIntervalMs()
        );
    }
}

