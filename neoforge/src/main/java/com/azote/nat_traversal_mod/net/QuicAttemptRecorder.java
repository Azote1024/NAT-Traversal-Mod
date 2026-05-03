package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.net.supabase.SupabaseQuicSessionClient;

import java.util.UUID;

final class QuicAttemptRecorder {
    private QuicAttemptRecorder() {
    }

    static String startAttempt(String roomName, String clientKey) {
        String attemptId = UUID.randomUUID().toString();
        SupabaseQuicSessionClient.markClientAttemptStarted(roomName, attemptId);
        SupabaseQuicSessionClient.upsertPeerAttempt(
                roomName,
                clientKey,
                attemptId,
                "unknown",
                "quic_try",
                "idle",
                "",
                false
        );
        SupabaseQuicSessionClient.upsertPeerAttemptSync(
                roomName,
                clientKey,
                attemptId,
                "",
                "",
                "",
                "",
                0,
                "attempt_started"
        );
        return attemptId;
    }

    static void markPunchSent(String roomName, String clientKey, String attemptId) {
        SupabaseQuicSessionClient.markClientPunchSent(roomName, attemptId);
        SupabaseQuicSessionClient.upsertPeerAttempt(
                roomName,
                clientKey,
                attemptId,
                "unknown",
                "quic_try",
                "client_probe_sent",
                "",
                false
        );
    }
}

