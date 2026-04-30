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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupabaseRoomsClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final Pattern HOST_IP_PATTERN = Pattern.compile("\"host_ip\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("\"host_port\"\\s*:\\s*(\\d+)");
    private static final Pattern PUBLIC_ENDPOINT_PATTERN = Pattern.compile("\"public_endpoint\"\\s*:\\s*\"([^\"]*)\"");
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
        String endpoint = supabaseUrl + "/rest/v1/rooms?select=host_ip,host_port,public_endpoint,updated_at"
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
        if (ageMillis < 0L || ageMillis > ROOM_FRESHNESS_TTL_MILLIS) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Room data is stale. room_name='{}', age_ms={}, ttl_ms={}. Fallback to original target.",
                    roomName,
                    ageMillis,
                    ROOM_FRESHNESS_TTL_MILLIS
            );
            return Optional.empty();
        }

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] Room data is fresh. room_name='{}', age_ms={}, ttl_ms={}",
                roomName,
                ageMillis,
                ROOM_FRESHNESS_TTL_MILLIS
        );

        Optional<ResolvedTarget> publicEndpointTarget = parsePublicEndpoint(body, roomName);
        if (publicEndpointTarget.isPresent()) {
            return publicEndpointTarget;
        }

        if (Config.stunEnabled()) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] public_endpoint is not used. room_name='{}'. Fallback to host_ip:host_port.",
                    roomName
            );
        }

        return Optional.of(new ResolvedTarget(hostIp, hostPort));
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

        String[] parts = publicEndpoint.split(":", 2);
        if (parts.length != 2) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid public_endpoint format. room_name='{}', public_endpoint='{}'. Ignore public_endpoint.",
                    roomName,
                    publicEndpoint
            );
            return Optional.empty();
        }

        String endpointHost = parts[0].trim();
        int endpointPort;
        try {
            endpointPort = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ignored) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid public_endpoint port. room_name='{}', public_endpoint='{}'. Ignore public_endpoint.",
                    roomName,
                    publicEndpoint
            );
            return Optional.empty();
        }

        if (endpointHost.isBlank() || endpointPort < 1 || endpointPort > 65535) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Invalid public_endpoint value. room_name='{}', public_endpoint='{}'. Ignore public_endpoint.",
                    roomName,
                    publicEndpoint
            );
            return Optional.empty();
        }

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] Use public_endpoint from room. room_name='{}', target='{}:{}'",
                roomName,
                endpointHost,
                endpointPort
        );
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
