package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First implementation slice for QUIC-first routing.
 *
 * This manager currently handles room payload parsing and controlled multi-attempt flow.
 * Transport establishment is intentionally minimal and returns empty when no direct hint exists.
 */
public final class QuicP2pManager {
    private enum QuicRouteState {
        DISABLED,
        HINT_MISSING,
        NOT_READY,
        INVALID_ENDPOINT,
        BLOCKED_BY_RELAY_ENDPOINT,
        TRANSPORT_UNAVAILABLE,
        ATTEMPTING,
        ESTABLISHED,
        EXHAUSTED
    }

    private static final Pattern QUIC_ENDPOINT_PATTERN = Pattern.compile("\"quic_endpoint\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern QUIC_STATUS_PATTERN = Pattern.compile("\"quic_status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PUNCH_ENDPOINT_PATTERN = Pattern.compile("\"punch_endpoint\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PUNCH_STATUS_PATTERN = Pattern.compile("\"punch_status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PUNCH_TOKEN_PATTERN = Pattern.compile("\"punch_token\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern RELAY_ENDPOINT_PATTERN = Pattern.compile("\"relay_endpoint\"\\s*:\\s*\"([^\"]*)\"");
    private static final QuicTransport TRANSPORT = createTransport();
    private static final String CLIENT_KEY = UUID.randomUUID().toString();

    private QuicP2pManager() {
    }

    static String clientKey() {
        return CLIENT_KEY;
    }

    private static QuicTransport createTransport() {
        try {
            if (!QuicRuntimeClasspath.ensureAvailable()) {
                throw new IllegalStateException("QUIC runtime classes are unavailable");
            }
            return new NettyQuicTransport();
        } catch (Throwable throwable) {
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] QUIC transport backend is unavailable. Fallback to relay/public routes.");
            return new NoopQuicTransport();
        }
    }

    public static Optional<ResolvedTarget> tryResolveFromRoom(String body, String roomName) {
        if (!Config.quicEnabled()) {
            logState(roomName, QuicRouteState.DISABLED, "quic_enabled=false");
            return Optional.empty();
        }

        Optional<String> statusValue = findFirst(QUIC_STATUS_PATTERN, body);
        if (statusValue.isEmpty()) {
            logState(roomName, QuicRouteState.HINT_MISSING, "quic_status is missing");
            return Optional.empty();
        }

        String status = statusValue.get().trim();
        if (!"ready".equalsIgnoreCase(status)) {
            if (!status.isEmpty()) {
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] QUIC hint exists but status is not ready. room_name='{}', quic_status='{}'",
                        roomName,
                        status
                );
            }
            logState(roomName, QuicRouteState.NOT_READY, "quic_status=" + status);
            return Optional.empty();
        }

        Optional<String> endpointValue = findFirst(QUIC_ENDPOINT_PATTERN, body);
        if (endpointValue.isEmpty()) {
            logState(roomName, QuicRouteState.HINT_MISSING, "quic_endpoint is missing");
            return Optional.empty();
        }

        String endpoint = endpointValue.get().trim();
        Matcher relayMatcher = RELAY_ENDPOINT_PATTERN.matcher(body);
        if (relayMatcher.find()) {
            String relayEndpoint = relayMatcher.group(1).trim();
            if (!relayEndpoint.isEmpty() && relayEndpoint.equalsIgnoreCase(endpoint)) {
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] Skip QUIC attempt because quic_endpoint matches relay_endpoint. room_name='{}'.",
                        roomName
                );
                logState(roomName, QuicRouteState.BLOCKED_BY_RELAY_ENDPOINT, "quic_endpoint matches relay_endpoint");
                return Optional.empty();
            }
        }

        return tryActivateEndpoint(endpoint, body, roomName);
    }

    public static Optional<ResolvedTarget> tryResolveFromSessionBody(String body, String roomName) {
        if (!Config.quicEnabled()) {
            logState(roomName, QuicRouteState.DISABLED, "quic_enabled=false");
            return Optional.empty();
        }

        Optional<String> statusValue = findFirst(QUIC_STATUS_PATTERN, body);
        if (statusValue.isEmpty()) {
            logState(roomName, QuicRouteState.HINT_MISSING, "quic_sessions.quic_status is missing");
            return Optional.empty();
        }

        String status = statusValue.get().trim();
        if (!"ready".equalsIgnoreCase(status)) {
            logState(roomName, QuicRouteState.NOT_READY, "quic_sessions.quic_status=" + status);
            return Optional.empty();
        }

        Optional<String> endpointValue = findFirst(QUIC_ENDPOINT_PATTERN, body);
        if (endpointValue.isEmpty()) {
            logState(roomName, QuicRouteState.HINT_MISSING, "quic_sessions.quic_endpoint is missing");
            return Optional.empty();
        }

        return tryActivateEndpoint(endpointValue.get().trim(), body, roomName);
    }

    private static Optional<ResolvedTarget> tryActivateEndpoint(String endpoint, String roomBody, String roomName) {
        Optional<RelayEndpoint> parsedEndpoint = RelayEndpoint.parse(endpoint, "quic_endpoint");
        if (parsedEndpoint.isEmpty()) {
            logState(roomName, QuicRouteState.INVALID_ENDPOINT, "invalid quic_endpoint=" + endpoint);
            return Optional.empty();
        }
        RelayEndpoint quicEndpoint = parsedEndpoint.get();
        String attemptId = UUID.randomUUID().toString();
        SupabaseQuicSessionClient.markClientAttemptStarted(roomName, attemptId);
        SupabaseQuicSessionClient.upsertPeerAttempt(
                roomName,
                CLIENT_KEY,
                attemptId,
                "unknown",
                "quic_try",
                "idle",
                "",
                false
        );

        prepareHolePunch(roomBody, roomName, quicEndpoint, attemptId);

        if (QuicDirectConnectorFactory.isOperational()) {
            QuicDirectRouteContext.set(quicEndpoint, attemptId);
            logState(roomName, QuicRouteState.ESTABLISHED, "direct connector prepared");
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] QUIC direct route prepared. room_name='{}', attempt_id='{}', endpoint='{}:{}'",
                    roomName,
                    attemptId,
                    quicEndpoint.host(),
                    quicEndpoint.port()
            );
            return Optional.of(new ResolvedTarget(quicEndpoint.host(), quicEndpoint.port()));
        }

        if (!TRANSPORT.isOperational()) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] QUIC transport is not operational yet. room_name='{}'. Fallback to next route.",
                    roomName
            );
            logState(roomName, QuicRouteState.TRANSPORT_UNAVAILABLE, "transport unavailable");
            return Optional.empty();
        }

        int attempts = Config.quicAttempts();
        int intervalMs = Config.quicAttemptIntervalMs();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            logState(roomName, QuicRouteState.ATTEMPTING, "attempt=" + attempt + "/" + attempts);
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] QUIC attempt {}/{} planned. room_name='{}', endpoint='{}', tls_mode='{}'.",
                    attempt,
                    attempts,
                    roomName,
                    endpoint,
                    Config.quicTlsMode()
            );

            Optional<ResolvedTarget> localTarget = TRANSPORT.tryActivate(quicEndpoint, roomName);
            if (localTarget.isPresent()) {
                logState(roomName, QuicRouteState.ESTABLISHED, "local target selected");
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] QUIC route selected. room_name='{}', target='{}:{}'",
                        roomName,
                        localTarget.get().hostIp(),
                        localTarget.get().hostPort()
                );
                return localTarget;
            }

            if (attempt < attempts) {
                sleepQuietly(intervalMs);
            }
        }

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] QUIC attempts exhausted for room_name='{}'. Fallback to next route.",
                roomName
        );
        logState(roomName, QuicRouteState.EXHAUSTED, "all attempts failed");
        return Optional.empty();
    }

    private static void prepareHolePunch(String roomBody, String roomName, RelayEndpoint quicEndpoint, String attemptId) {
        Optional<String> punchStatusValue = findFirst(PUNCH_STATUS_PATTERN, roomBody).map(String::trim);
        if (punchStatusValue.isEmpty() || !shouldSendPunch(punchStatusValue.get())) {
            return;
        }

        Optional<String> punchEndpointValue = findFirst(PUNCH_ENDPOINT_PATTERN, roomBody)
                .or(() -> Optional.of(quicEndpoint.host() + ":" + quicEndpoint.port()));
        Optional<RelayEndpoint> punchEndpoint = punchEndpointValue.flatMap(value -> RelayEndpoint.parse(value.trim(), "punch_endpoint"));
        if (punchEndpoint.isEmpty()) {
            return;
        }

        String punchToken = findFirst(PUNCH_TOKEN_PATTERN, roomBody).orElse("");
        boolean punched = UdpHolePunchClient.oneShotPunch(punchEndpoint.get(), roomName, punchToken, 3, 120);
        if (punched) {
            SupabaseQuicSessionClient.markClientPunchSent(roomName, attemptId);
            SupabaseQuicSessionClient.upsertPeerAttempt(
                    roomName,
                    CLIENT_KEY,
                    attemptId,
                    "unknown",
                    "quic_try",
                    "client_probe_sent",
                    "",
                    false
            );
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] One-shot UDP hole punch sent. room_name='{}', attempt_id='{}', endpoint='{}:{}'",
                    roomName,
                    attemptId,
                    punchEndpoint.get().host(),
                    punchEndpoint.get().port()
            );
        }
    }

    private static boolean shouldSendPunch(String punchStatus) {
        String status = punchStatus == null ? "" : punchStatus.trim().toLowerCase();
        return "ready".equals(status)
                || "probing".equals(status)
                || "client_probe_sent".equals(status);
    }

    private static Optional<String> findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1));
    }

    private static void logState(String roomName, QuicRouteState state, String detail) {
        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] QUIC route state={}, room_name='{}', detail='{}'",
                state,
                roomName,
                detail
        );
    }


    private static void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}


