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

public final class SupabaseRoomsPublisher {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private SupabaseRoomsPublisher() {
    }

    public static void publishOpenRoom(int hostPort) {
        PublishConfig config = loadPublishConfig();
        if (config == null) {
            return;
        }

        if (hostPort < 1 || hostPort > 65535) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Skip room publish: invalid host port {}.", hostPort);
            return;
        }

        String endpoint = config.supabaseUrl + "/rest/v1/rooms";
        String body = "{" +
                "\"room_name\":\"" + jsonEscape(config.roomName) + "\"," +
                "\"host_name\":\"" + jsonEscape(config.hostName) + "\"," +
                "\"host_ip\":\"" + jsonEscape(config.hostIp) + "\"," +
                "\"host_port\":" + hostPort + "," +
                "\"status\":\"open\"" +
                "}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(4))
                .header("apikey", config.supabaseKey)
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        if (sendRequest(request)) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] Room published. room_name='{}' -> {}:{}",
                    config.roomName,
                    config.hostIp,
                    hostPort
            );
        }
    }

    public static void closeRoom() {
        PublishConfig config = loadPublishConfig();
        if (config == null) {
            return;
        }

        String encodedRoomName = URLEncoder.encode(config.roomName, StandardCharsets.UTF_8);
        String endpoint = config.supabaseUrl + "/rest/v1/rooms?room_name=eq." + encodedRoomName;
        String body = "{\"status\":\"closed\"}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(4))
                .header("apikey", config.supabaseKey)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        if (sendRequest(request)) {
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] Room closed. room_name='{}'", config.roomName);
        }
    }

    private static PublishConfig loadPublishConfig() {
        String supabaseUrl = Config.supabaseUrl();
        String supabaseKey = Config.supabaseKey();
        String roomName = Config.roomName();
        String hostName = Config.publishHostName();
        String hostIp = Config.publishHostIp();

        if (supabaseUrl.isBlank() || supabaseKey.isBlank() || roomName.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Skip room publish: supabase config is incomplete.");
            return null;
        }

        if (hostName.isBlank() || hostIp.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Skip room publish: set publish_host_name and publish_host_ip in config.");
            return null;
        }

        return new PublishConfig(supabaseUrl, supabaseKey, roomName, hostName, hostIp);
    }

    private static boolean sendRequest(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return true;
            }

            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] Room publish failed. status={}, body='{}'",
                    response.statusCode(),
                    trimBody(response.body())
            );
            return false;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Room publish exception.", exception);
            return false;
        } catch (RuntimeException exception) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Unexpected publish error.", exception);
            return false;
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String trimBody(String body) {
        if (body == null) {
            return "";
        }
        String singleLine = body.replace('\n', ' ').replace('\r', ' ');
        if (singleLine.length() <= 200) {
            return singleLine;
        }
        return singleLine.substring(0, 200) + "...";
    }

    private record PublishConfig(String supabaseUrl, String supabaseKey, String roomName, String hostName, String hostIp) {
    }
}
