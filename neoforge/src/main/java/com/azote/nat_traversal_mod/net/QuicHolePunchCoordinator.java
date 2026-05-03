package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.net.supabase.SupabaseQuicSessionClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QuicHolePunchCoordinator {
    private static final int PUNCH_WINDOW_DELAY_MS = 600;
    private static final Pattern PUNCH_ENDPOINT_PATTERN = Pattern.compile("\"punch_endpoint\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PUNCH_STATUS_PATTERN = Pattern.compile("\"punch_status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PUNCH_TOKEN_PATTERN = Pattern.compile("\"punch_token\"\\s*:\\s*\"([^\"]*)\"");

    private QuicHolePunchCoordinator() {
    }

    static void prepareOneShotPunch(String roomBody, String roomName, RelayEndpoint quicEndpoint, String attemptId, String clientKey) {
        Optional<String> punchStatusValue = findFirst(PUNCH_STATUS_PATTERN, roomBody).map(String::trim);
        if (punchStatusValue.isEmpty() || !shouldSendPunch(punchStatusValue.get())) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_punch phase=skip error_code=invalid_punch_status room_name='{}' attempt_id='{}' punch_status='{}'",
                    roomName,
                    attemptId,
                    punchStatusValue.orElse("")
            );
            return;
        }

        Optional<String> punchEndpointValue = findFirst(PUNCH_ENDPOINT_PATTERN, roomBody)
                .or(() -> Optional.of(quicEndpoint.host() + ":" + quicEndpoint.port()));
        Optional<RelayEndpoint> punchEndpoint = punchEndpointValue.flatMap(value -> RelayEndpoint.parse(value.trim(), "punch_endpoint"));
        if (punchEndpoint.isEmpty()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_punch phase=skip error_code=invalid_punch_endpoint room_name='{}' attempt_id='{}' punch_endpoint='{}'",
                    roomName,
                    attemptId,
                    punchEndpointValue.orElse("")
            );
            return;
        }

        String syncToken = UUID.randomUUID().toString();
        Instant windowOpenInstant = Instant.now().plusMillis(PUNCH_WINDOW_DELAY_MS);
        String windowOpenedAt = windowOpenInstant.toString();
        int windowMs = 2200;
        String clientPublicEndpoint = resolveClientPublicEndpoint();
        SupabaseQuicSessionClient.upsertPeerAttemptSync(
                roomName,
                clientKey,
                attemptId,
                clientPublicEndpoint,
                "",
                syncToken,
                windowOpenedAt,
                windowMs,
                "punch_window_opened"
        );

        long waitMillis = Duration.between(Instant.now(), windowOpenInstant).toMillis();
        if (waitMillis > 0L) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                SupabaseQuicSessionClient.upsertPeerAttemptSync(
                        roomName,
                        clientKey,
                        attemptId,
                        clientPublicEndpoint,
                        "",
                        syncToken,
                        windowOpenedAt,
                        windowMs,
                        QuicErrorCodes.SYNC_MISS
                );
                NatTraversalMod.LOGGER.info(
                        "[nat-traversal-mod] quic_punch phase=window_wait_interrupted error_code=sync_miss room_name='{}' attempt_id='{}'",
                        roomName,
                        attemptId
                );
                return;
            }
        }

        String punchToken = findFirst(PUNCH_TOKEN_PATTERN, roomBody).orElse("");
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] quic_punch phase=window_ready room_name='{}' attempt_id='{}' endpoint='{}:{}' sync_token='{}' token_present={} window_opened_at='{}' window_ms={}",
                roomName,
                attemptId,
                punchEndpoint.get().host(),
                punchEndpoint.get().port(),
                syncToken,
                !punchToken.isBlank(),
                windowOpenedAt,
                windowMs
        );
    }

    private static boolean shouldSendPunch(String punchStatus) {
        String status = punchStatus == null ? "" : punchStatus.trim().toLowerCase();
        return "ready".equals(status)
                || "probing".equals(status)
                || "client_probe_sent".equals(status);
    }

    private static String resolveClientPublicEndpoint() {
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        if (!runtimeConfig.stunEnabled()) {
            return "";
        }
        return StunClient.resolvePublicEndpoint(runtimeConfig.stunServer(), runtimeConfig.stunTimeoutMs()).orElse("");
    }

    private static Optional<String> findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1));
    }
}

