package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First implementation slice for QUIC-first routing.
 *
 * This manager currently handles room payload parsing and controlled multi-attempt flow.
 * Transport establishment is intentionally minimal and returns empty when no direct hint exists.
 */
public final class QuicP2pManager {
    private static final Pattern QUIC_ENDPOINT_PATTERN = Pattern.compile("\\\"quic_endpoint\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern QUIC_STATUS_PATTERN = Pattern.compile("\\\"quic_status\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern RELAY_ENDPOINT_PATTERN = Pattern.compile("\\\"relay_endpoint\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");

    private QuicP2pManager() {
    }

    public static Optional<ResolvedTarget> tryResolveFromRoom(String body, String roomName) {
        if (!Config.quicEnabled()) {
            return Optional.empty();
        }

        Matcher statusMatcher = QUIC_STATUS_PATTERN.matcher(body);
        if (statusMatcher.find()) {
            String status = statusMatcher.group(1).trim();
            if (!status.isEmpty() && !"ready".equalsIgnoreCase(status)) {
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] QUIC hint exists but status is not ready. room_name='{}', quic_status='{}'.",
                        roomName,
                        status
                );
                return Optional.empty();
            }
        }

        Matcher endpointMatcher = QUIC_ENDPOINT_PATTERN.matcher(body);
        if (!endpointMatcher.find()) {
            return Optional.empty();
        }

        String endpoint = endpointMatcher.group(1).trim();
        Matcher relayMatcher = RELAY_ENDPOINT_PATTERN.matcher(body);
        if (relayMatcher.find()) {
            String relayEndpoint = relayMatcher.group(1).trim();
            if (!relayEndpoint.isEmpty() && relayEndpoint.equalsIgnoreCase(endpoint)) {
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] Skip QUIC attempt because quic_endpoint matches relay_endpoint. room_name='{}'.",
                        roomName
                );
                return Optional.empty();
            }
        }

        Optional<RelayEndpoint> parsedEndpoint = RelayEndpoint.parse(endpoint, "quic_endpoint");
        if (parsedEndpoint.isEmpty()) {
            return Optional.empty();
        }

        int attempts = Config.quicAttempts();
        int intervalMs = Config.quicAttemptIntervalMs();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] QUIC attempt {}/{} planned. room_name='{}', endpoint='{}', tls_mode='{}'.",
                    attempt,
                    attempts,
                    roomName,
                    endpoint,
                    Config.quicTlsMode()
            );

            // Implementation note: tunnel establishment is added in the next slice.
            if (attempt < attempts) {
                sleepQuietly(intervalMs);
            }
        }

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] QUIC attempts exhausted for room_name='{}'. Fallback to next route.",
                roomName
        );
        return Optional.empty();
    }

    private static void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}


