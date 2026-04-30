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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupabaseRoomsClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final Pattern HOST_IP_PATTERN = Pattern.compile("\"host_ip\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("\"host_port\"\\s*:\\s*(\\d+)");

    private SupabaseRoomsClient() {
    }

    public static Optional<ResolvedTarget> resolveRoom() {
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
        String endpoint = supabaseUrl + "/rest/v1/rooms?select=host_ip,host_port"
                + "&room_name=eq." + encodedRoomName
                + "&status=eq.open";

        Optional<ResolvedTarget> firstTry = resolveRoomOnce(endpoint, supabaseKey, roomName);
        if (firstTry.isPresent()) {
            return firstTry;
        }

        Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] Room resolve retry once. room_name='{}'", roomName);
        try {
            Thread.sleep(250L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

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

            Optional<ResolvedTarget> target = parseResponse(response.body());
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

    private static Optional<ResolvedTarget> parseResponse(String body) {
        Matcher hostIpMatcher = HOST_IP_PATTERN.matcher(body);
        Matcher hostPortMatcher = HOST_PORT_PATTERN.matcher(body);
        if (!hostIpMatcher.find() || !hostPortMatcher.find()) {
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

        return Optional.of(new ResolvedTarget(hostIp, hostPort));
    }

    public record ResolvedTarget(String hostIp, int hostPort) {
    }
}
