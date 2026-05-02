package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupabaseRoomsClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final Pattern HOST_IP_PATTERN = Pattern.compile("\"host_ip\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("\"host_port\"\\s*:\\s*(\\d+)");
    private static final Pattern PUBLIC_ENDPOINT_PATTERN = Pattern.compile("\"public_endpoint\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern RELAY_ENDPOINT_PATTERN = Pattern.compile("\"relay_endpoint\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern RELAY_STATUS_PATTERN = Pattern.compile("\"relay_status\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern HOST_NAT_TYPE_PATTERN = Pattern.compile("\"host_nat_type\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern UPDATED_AT_PATTERN = Pattern.compile("\"updated_at\"\\s*:\\s*\"([^\"]+)\"");
    private static final long ROOM_FRESHNESS_TTL_MILLIS = 180_000L;

    private SupabaseRoomsClient() {
    }

    public static Optional<ResolvedTarget> resolve() {
        String supabaseUrl = Config.supabaseUrl();
        String supabaseKey = Config.supabaseKey();
        String roomName = Config.roomName();

        if (supabaseUrl.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] supabase_url is empty. Fallback to original target.");
            return Optional.empty();
        }

        if (supabaseKey.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] supabase_key is empty. Fallback to original target.");
            return Optional.empty();
        }

        if (roomName.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] room_name is empty. Fallback to original target.");
            return Optional.empty();
        }

        String encodedRoomName = URLEncoder.encode(roomName, StandardCharsets.UTF_8);
        String endpoint = supabaseUrl + "/rest/v1/rooms?select=host_ip,host_port,public_endpoint,relay_endpoint,relay_status,host_nat_type,candidates,updated_at"
                + "&room_name=eq." + encodedRoomName
                + "&status=eq.open";


        return resolveRoomOnce(endpoint, supabaseKey, roomName);
    }

    private static Optional<ResolvedTarget> resolveRoomOnce(String endpoint, String supabaseKey, String roomName) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(4))
                .header("apikey", supabaseKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Nat_traversal_mod.LOGGER.warn(
                        "[nat-traversal-mod] Supabase request failed. status={}, room_name='{}'. Fallback to original target.",
                        response.statusCode(),
                        roomName
                );
                return Optional.empty();
            }

            Optional<ResolvedTarget> target = parseResponse(response.body(), roomName);
            if (target.isEmpty()) {
                Nat_traversal_mod.LOGGER.warn(
                        "[nat-traversal-mod] Room not found or invalid data. room_name='{}'. Fallback to original target.",
                        roomName
                );
                return Optional.empty();
            }

            return target;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Supabase request exception. room_name='{}'. Fallback to original target.",
                    roomName,
                    exception
            );
            return Optional.empty();
        } catch (RuntimeException exception) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Unexpected error while reading room. room_name='{}'. Fallback to original target.",
                    roomName,
                    exception
            );
            return Optional.empty();
        }
    }

    private static Optional<ResolvedTarget> parseResponse(String body, String roomName) {
        Matcher hostIpMatcher = HOST_IP_PATTERN.matcher(body);
        Matcher hostPortMatcher = HOST_PORT_PATTERN.matcher(body);
        Matcher updatedAtMatcher = UPDATED_AT_PATTERN.matcher(body);
        if (!hostIpMatcher.find() || !hostPortMatcher.find() || !updatedAtMatcher.find()) {
            return Optional.empty();
        }

        String hostIp = hostIpMatcher.group(1);
        int hostPort;
        try {
            hostPort = Integer.parseInt(hostPortMatcher.group(1));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }

        if (hostPort < 1 || hostPort > 65535) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid host_port={} from Supabase. Fallback to original target.",
                    hostPort
            );
            return Optional.empty();
        }

        if (hostIp.isBlank()) {
            return Optional.empty();
        }

        String updatedAtRaw = updatedAtMatcher.group(1);
        Instant updatedAt = parseUpdatedAt(updatedAtRaw, roomName);
        if (updatedAt == null) {
            return Optional.empty();
        }

        long ageMillis = Duration.between(updatedAt, Instant.now()).toMillis();
        boolean isFresh = ageMillis >= 0L && ageMillis <= ROOM_FRESHNESS_TTL_MILLIS;
        if (!isFresh) {
            if (!Config.quicFirstMode()) {
                Nat_traversal_mod.LOGGER.warn(
                        "[nat-traversal-mod] Room data is stale. room_name='{}', age_ms={}, ttl_ms={}. Fallback to original target.",
                        roomName,
                        ageMillis,
                        ROOM_FRESHNESS_TTL_MILLIS
                );
                return Optional.empty();
            }

            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] Room data is stale but quic_first mode allows QUIC route attempt. room_name='{}', age_ms={}, ttl_ms={}",
                    roomName,
                    ageMillis,
                    ROOM_FRESHNESS_TTL_MILLIS
            );
        }

        if (isFresh) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] Room data is fresh. room_name='{}', age_ms={}, ttl_ms={}",
                    roomName,
                    ageMillis,
                    ROOM_FRESHNESS_TTL_MILLIS
            );
        }

        String hostNatType = extractNatType(body);

        if (Config.quicFirstMode()) {
            if ("symmetric".equals(hostNatType)) {
                String forcedAttemptId = UUID.randomUUID().toString();
                SupabaseQuicSessionClient.markClientAttemptStarted(roomName, forcedAttemptId);
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] host_nat_type=symmetric. skip QUIC and force relay-first path. room_name='{}'.",
                        roomName
                );
                SupabaseQuicSessionClient.markRouteDecision(roomName, "relay_forced", "host_symmetric_nat");
                SupabaseQuicSessionClient.upsertPeerAttempt(
                        roomName,
                        QuicP2pManager.clientKey(),
                        forcedAttemptId,
                        "symmetric",
                        "relay_forced",
                        "idle",
                        "host_symmetric_nat",
                        true
                );

                Optional<ResolvedTarget> relayEndpointTarget = parseRelayEndpoint(body, roomName);
                if (relayEndpointTarget.isPresent()) {
                    return relayEndpointTarget;
                }

                Nat_traversal_mod.LOGGER.warn(
                        "[nat-traversal-mod] relay_forced by symmetric NAT but relay endpoint unavailable. room_name='{}'. Continue fallback chain.",
                        roomName
                );
            } else {
                SupabaseQuicSessionClient.markRouteDecision(roomName, "quic_try", "");
            }

            if (!"symmetric".equals(hostNatType)) {
                Optional<String> quicSessionBody = SupabaseQuicSessionClient.fetchSessionBody(roomName);
                if (quicSessionBody.isPresent()) {
                    Optional<ResolvedTarget> quicSessionTarget = QuicP2pManager.tryResolveFromSessionBody(quicSessionBody.get(), roomName);
                    if (quicSessionTarget.isPresent()) {
                        return quicSessionTarget;
                    }
                }

                Optional<ResolvedTarget> quicTarget = QuicP2pManager.tryResolveFromRoom(body, roomName);
                if (quicTarget.isPresent()) {
                    return quicTarget;
                }
            }

            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] QUIC route unavailable. room_name='{}'. Try relay/public fallback.",
                    roomName
            );

            Optional<ResolvedTarget> relayEndpointTarget = parseRelayEndpoint(body, roomName);
            if (relayEndpointTarget.isPresent()) {
                return relayEndpointTarget;
            }

            Optional<ResolvedTarget> publicEndpointTarget = parsePublicEndpoint(body, roomName);
            if (publicEndpointTarget.isPresent()) {
                return publicEndpointTarget;
            }
        } else if (Config.relayFirstMode()) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] relay_priority_mode=relay_first. Try relay endpoint before public_endpoint. room_name='{}'.",
                    roomName
            );

            Optional<ResolvedTarget> relayEndpointTarget = parseRelayEndpoint(body, roomName);
            if (relayEndpointTarget.isPresent()) {
                return relayEndpointTarget;
            }

            Optional<ResolvedTarget> publicEndpointTarget = parsePublicEndpoint(body, roomName);
            if (publicEndpointTarget.isPresent()) {
                return publicEndpointTarget;
            }
        } else {
            Optional<ResolvedTarget> publicEndpointTarget = parsePublicEndpoint(body, roomName);
            if (publicEndpointTarget.isPresent()) {
                return publicEndpointTarget;
            }

            Optional<ResolvedTarget> relayEndpointTarget = parseRelayEndpoint(body, roomName);
            if (relayEndpointTarget.isPresent()) {
                return relayEndpointTarget;
            }
        }

        if (Config.stunEnabled()) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] public_endpoint is not used. room_name='{}'. Fallback to host_ip:host_port.",
                    roomName
            );
        }

        return Optional.of(new ResolvedTarget(hostIp, hostPort));
    }

    private static String extractNatType(String body) {
        Matcher matcher = HOST_NAT_TYPE_PATTERN.matcher(body);
        if (!matcher.find()) {
            return "unknown";
        }

        String natType = matcher.group(1).trim().toLowerCase();
        if (natType.equals("open") || natType.equals("port_restricted") || natType.equals("symmetric")) {
            return natType;
        }
        return "unknown";
    }

    private static Optional<ResolvedTarget> parseRelayEndpoint(String body, String roomName) {
        if (!Config.relayClientConnectorEnabled()) {
            return Optional.empty();
        }

        Matcher relayStatusMatcher = RELAY_STATUS_PATTERN.matcher(body);
        if (!relayStatusMatcher.find()) {
            return Optional.empty();
        }

        String relayStatus = relayStatusMatcher.group(1).trim();
        if (!"ready".equalsIgnoreCase(relayStatus)) {
            if (!relayStatus.isEmpty()) {
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] relay_status is not ready. room_name='{}', relay_status='{}'.",
                        roomName,
                        relayStatus
                );
            }
            return Optional.empty();
        }

        Matcher relayEndpointMatcher = RELAY_ENDPOINT_PATTERN.matcher(body);
        if (!relayEndpointMatcher.find()) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] relay_status=ready but relay_endpoint is missing. room_name='{}'.",
                    roomName
            );
            return Optional.empty();
        }

        String relayEndpoint = relayEndpointMatcher.group(1).trim();
        if (relayEndpoint.isBlank()) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] relay_status=ready but relay_endpoint is empty. room_name='{}'.",
                    roomName
            );
            return Optional.empty();
        }

        if (!RelayClientConnectorManager.ensureStarted()) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] relay_status=ready but local relay client connector is not running. room_name='{}'.",
                    roomName
            );
            return Optional.empty();
        }

        int localRelayPort = Config.relayClientLocalPort();
        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] Use local relay client connector. room_name='{}', relay_endpoint='{}', target='127.0.0.1:{}'",
                roomName,
                relayEndpoint,
                localRelayPort
        );
        return Optional.of(new ResolvedTarget("127.0.0.1", localRelayPort));
    }

    private static Optional<ResolvedTarget> parsePublicEndpoint(String body, String roomName) {
        Matcher publicEndpointMatcher = PUBLIC_ENDPOINT_PATTERN.matcher(body);
        if (!publicEndpointMatcher.find()) {
            if (Config.stunEnabled()) {
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] public_endpoint key is missing in room payload. room_name='{}'.",
                        roomName
                );
            }
            return Optional.empty();
        }

        String publicEndpoint = publicEndpointMatcher.group(1).trim();
        if (publicEndpoint.isEmpty()) {
            if (Config.stunEnabled()) {
                Nat_traversal_mod.LOGGER.info(
                        "[nat-traversal-mod] public_endpoint is empty. room_name='{}'.",
                        roomName
                );
            }
            return Optional.empty();
        }

        Optional<ResolvedTarget> parsed = parseEndpoint(publicEndpoint, roomName, "public_endpoint");
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] Use public_endpoint from room. room_name='{}', target='{}:{}'",
                roomName,
                parsed.get().hostIp(),
                parsed.get().hostPort()
        );
        return parsed;
    }

    private static Optional<ResolvedTarget> parseEndpoint(String endpoint, String roomName, String endpointName) {
        String[] parts = endpoint.split(":", 2);
        if (parts.length != 2) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid {} format. room_name='{}', {}='{}'. Ignore {}.",
                    endpointName,
                    roomName,
                    endpointName,
                    endpoint,
                    endpointName
            );
            return Optional.empty();
        }

        String endpointHost = parts[0].trim();
        int endpointPort;
        try {
            endpointPort = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ignored) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid {} port. room_name='{}', {}='{}'. Ignore {}.",
                    endpointName,
                    roomName,
                    endpointName,
                    endpoint,
                    endpointName
            );
            return Optional.empty();
        }

        if (endpointHost.isBlank() || endpointPort < 1 || endpointPort > 65535) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid {} value. room_name='{}', {}='{}'. Ignore {}.",
                    endpointName,
                    roomName,
                    endpointName,
                    endpoint,
                    endpointName
            );
            return Optional.empty();
        }

        return Optional.of(new ResolvedTarget(endpointHost, endpointPort));
    }

    private static Instant parseUpdatedAt(String updatedAtRaw, String roomName) {
        try {
            return OffsetDateTime.parse(updatedAtRaw).toInstant();
        } catch (DateTimeParseException exception) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid updated_at format. room_name='{}', updated_at='{}'. Fallback to original target.",
                    roomName,
                    updatedAtRaw
            );
            return null;
        }
    }

}

