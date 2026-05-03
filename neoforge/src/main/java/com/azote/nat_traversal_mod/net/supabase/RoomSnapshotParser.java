package com.azote.nat_traversal_mod.net.supabase;

import com.azote.nat_traversal_mod.NatTraversalMod;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RoomSnapshotParser {
    private static final Pattern HOST_IP_PATTERN = Pattern.compile("\"host_ip\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("\"host_port\"\\s*:\\s*(\\d+)");
    private static final Pattern HOST_NAT_TYPE_PATTERN = Pattern.compile("\"host_nat_type\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern UPDATED_AT_PATTERN = Pattern.compile("\"updated_at\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PUBLIC_ENDPOINT_PATTERN = Pattern.compile("\"public_endpoint\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern RELAY_ENDPOINT_PATTERN = Pattern.compile("\"relay_endpoint\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern RELAY_STATUS_PATTERN = Pattern.compile("\"relay_status\"\\s*:\\s*\"([^\"]*)\"");

    private RoomSnapshotParser() {
    }

    static Optional<RoomSnapshot> parse(String body, String roomName) {
        Optional<String> hostIp = findFirst(HOST_IP_PATTERN, body);
        Optional<String> hostPortRaw = findFirst(HOST_PORT_PATTERN, body);
        Optional<String> updatedAtRaw = findFirst(UPDATED_AT_PATTERN, body);
        if (hostIp.isEmpty() || hostPortRaw.isEmpty() || updatedAtRaw.isEmpty()) {
            return Optional.empty();
        }

        int hostPort;
        try {
            hostPort = Integer.parseInt(hostPortRaw.get());
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }

        if (hostPort < 1 || hostPort > 65535) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid host_port={} from Supabase. Fallback to original target.",
                    hostPort
            );
            return Optional.empty();
        }

        if (hostIp.get().isBlank()) {
            return Optional.empty();
        }

        Instant updatedAt;
        try {
            updatedAt = OffsetDateTime.parse(updatedAtRaw.get()).toInstant();
        } catch (DateTimeParseException exception) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid updated_at format. room_name='{}', updated_at='{}'. Fallback to original target.",
                    roomName,
                    updatedAtRaw.get()
            );
            return Optional.empty();
        }

        Optional<String> publicEndpoint = findPublicEndpoint(body);
        Optional<String> relayEndpoint = findRelayEndpoint(body);
        Optional<String> relayStatus = findRelayStatus(body);
        String hostNatType = normalizeHostNatType(findFirst(HOST_NAT_TYPE_PATTERN, body).orElse(""));

        return Optional.of(new RoomSnapshot(body, hostIp.get(), hostPort, hostNatType, updatedAt, publicEndpoint, relayEndpoint, relayStatus));
    }

    private static String normalizeHostNatType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.equals("open") || normalized.equals("port_restricted") || normalized.equals("symmetric")) {
            return normalized;
        }
        return "unknown";
    }

    private static Optional<String> findPublicEndpoint(String body) {
        return findFirst(PUBLIC_ENDPOINT_PATTERN, body).map(String::trim);
    }

    private static Optional<String> findRelayEndpoint(String body) {
        return findFirst(RELAY_ENDPOINT_PATTERN, body).map(String::trim);
    }

    private static Optional<String> findRelayStatus(String body) {
        return findFirst(RELAY_STATUS_PATTERN, body).map(String::trim);
    }

    private static Optional<String> findFirst(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1));
    }

    record RoomSnapshot(
            String rawBody,
            String hostIp,
            int hostPort,
            String hostNatType,
            Instant updatedAt,
            Optional<String> publicEndpoint,
            Optional<String> relayEndpoint,
            Optional<String> relayStatus
    ) {
    }
}


