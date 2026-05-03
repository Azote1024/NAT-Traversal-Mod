package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QuicRouteHintDecider {
    private static final Pattern QUIC_ENDPOINT_PATTERN = Pattern.compile("\"quic_endpoint\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HOST_PUBLIC_ENDPOINT_PATTERN = Pattern.compile("\"host_public_endpoint\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern QUIC_STATUS_PATTERN = Pattern.compile("\"quic_status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern RELAY_ENDPOINT_PATTERN = Pattern.compile("\"relay_endpoint\"\\s*:\\s*\"([^\"]*)\"");

    private QuicRouteHintDecider() {
    }

    static Optional<String> decideEndpointFromRoomBody(String body, String roomName) {
        if (!RuntimeConfigLoader.load().quicEnabled()) {
            QuicP2pManager.logState(roomName, QuicP2pManager.QuicRouteState.DISABLED, "quic_enabled=false");
            return Optional.empty();
        }

        Optional<String> statusValue = findFirst(QUIC_STATUS_PATTERN, body);
        if (statusValue.isEmpty()) {
            QuicP2pManager.logState(roomName, QuicP2pManager.QuicRouteState.HINT_MISSING, "quic_status is missing");
            return Optional.empty();
        }

        String status = statusValue.get().trim();
        if (!"ready".equalsIgnoreCase(status)) {
            if (!status.isEmpty()) {
                NatTraversalMod.LOGGER.info(
                        "[nat-traversal-mod] QUIC hint exists but status is not ready. room_name='{}', quic_status='{}'",
                        roomName,
                        status
                );
            }
            QuicP2pManager.logState(roomName, QuicP2pManager.QuicRouteState.NOT_READY, "quic_status=" + status);
            return Optional.empty();
        }

        Optional<String> endpointValue = findFirst(QUIC_ENDPOINT_PATTERN, body);
        if (endpointValue.isEmpty()) {
            QuicP2pManager.logState(roomName, QuicP2pManager.QuicRouteState.HINT_MISSING, "quic_endpoint is missing");
            return Optional.empty();
        }

        String endpoint = endpointValue.get().trim();
        Matcher relayMatcher = RELAY_ENDPOINT_PATTERN.matcher(body);
        if (relayMatcher.find()) {
            String relayEndpoint = relayMatcher.group(1).trim();
            if (!relayEndpoint.isEmpty() && relayEndpoint.equalsIgnoreCase(endpoint)) {
                NatTraversalMod.LOGGER.info(
                        "[nat-traversal-mod] Skip QUIC attempt because quic_endpoint matches relay_endpoint. room_name='{}'.",
                        roomName
                );
                QuicP2pManager.logState(
                        roomName,
                        QuicP2pManager.QuicRouteState.BLOCKED_BY_RELAY_ENDPOINT,
                        "quic_endpoint matches relay_endpoint"
                );
                return Optional.empty();
            }
        }

        return Optional.of(endpoint);
    }

    static Optional<String> decideEndpointFromSessionBody(String body, String roomName) {
        if (!RuntimeConfigLoader.load().quicEnabled()) {
            QuicP2pManager.logState(roomName, QuicP2pManager.QuicRouteState.DISABLED, "quic_enabled=false");
            return Optional.empty();
        }

        Optional<String> statusValue = findFirst(QUIC_STATUS_PATTERN, body);
        if (statusValue.isEmpty()) {
            QuicP2pManager.logState(roomName, QuicP2pManager.QuicRouteState.HINT_MISSING, "quic_sessions.quic_status is missing");
            return Optional.empty();
        }

        String status = statusValue.get().trim();
        if (!"ready".equalsIgnoreCase(status)) {
            QuicP2pManager.logState(roomName, QuicP2pManager.QuicRouteState.NOT_READY, "quic_sessions.quic_status=" + status);
            return Optional.empty();
        }

        Optional<String> endpointValue = findFirst(HOST_PUBLIC_ENDPOINT_PATTERN, body)
                .filter(value -> !value.trim().isEmpty())
                .or(() -> findFirst(QUIC_ENDPOINT_PATTERN, body));
        if (endpointValue.isEmpty()) {
            QuicP2pManager.logState(roomName, QuicP2pManager.QuicRouteState.HINT_MISSING, "quic_sessions endpoint is missing");
            return Optional.empty();
        }

        String endpoint = endpointValue.get().trim();
        if (findFirst(HOST_PUBLIC_ENDPOINT_PATTERN, body).map(String::trim).filter(endpoint::equals).isPresent()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] Use host_public_endpoint from quic_sessions. room_name='{}', endpoint='{}'",
                    roomName,
                    endpoint
            );
        }
        return Optional.of(endpoint);
    }

    private static Optional<String> findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1));
    }
}

