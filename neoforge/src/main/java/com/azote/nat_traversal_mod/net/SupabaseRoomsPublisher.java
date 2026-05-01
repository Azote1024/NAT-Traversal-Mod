package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

public final class SupabaseRoomsPublisher {
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 4000;

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
        String updatedAt = Instant.now().toString();
        String body = buildOpenRoomBody(config, hostPort, updatedAt);

        RequestResult result = sendJsonRequest(
                endpoint,
                "POST",
                body,
                config.supabaseKey,
                "resolution=merge-duplicates,return=minimal"
        );

        if (result.success) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] Room published. room_name='{}' -> {}:{}",
                    config.roomName,
                    config.hostIp,
                    hostPort
            );
            return;
        }

        Nat_traversal_mod.LOGGER.warn(
                "[nat-traversal-mod] Room publish failed. status={}, body='{}'",
                result.statusCode,
                trimBody(result.body)
        );
    }

    public static void closeRoomAsync() {
        Thread thread = new Thread(SupabaseRoomsPublisher::closeRoom, "nat-traversal-room-close");
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeRoom() {
        PublishConfig config = loadPublishConfig();
        if (config == null) {
            return;
        }

        String encodedRoomName = URLEncoder.encode(config.roomName, StandardCharsets.UTF_8);
        String endpoint = config.supabaseUrl + "/rest/v1/rooms?room_name=eq." + encodedRoomName;
        String updatedAt = Instant.now().toString();
        String body = "{" +
                "\"status\":\"closed\"," +
                "\"updated_at\":\"" + jsonEscape(updatedAt) + "\"" +
                "}";

        RequestResult result = sendJsonRequest(
                endpoint,
                "PATCH",
                body,
                config.supabaseKey,
                "return=minimal"
        );

        if (result.success) {
            Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] Room closed. room_name='{}'", config.roomName);
            return;
        }

        Nat_traversal_mod.LOGGER.warn(
                "[nat-traversal-mod] Room close failed. status={}, body='{}'",
                result.statusCode,
                trimBody(result.body)
        );
    }

    private static RequestResult sendJsonRequest(String endpoint, String method, String body, String supabaseKey, String prefer) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("apikey", supabaseKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Prefer", prefer);

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(bytes);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection, statusCode >= 200 && statusCode < 300);
            boolean success = statusCode >= 200 && statusCode < 300;
            return new RequestResult(success, statusCode, responseBody);
        } catch (IOException exception) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Room publish exception.", exception);
            return new RequestResult(false, -1, exception.getMessage());
        } catch (RuntimeException exception) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Unexpected publish error.", exception);
            return new RequestResult(false, -1, exception.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection connection, boolean success) {
        try (InputStream stream = success ? connection.getInputStream() : connection.getErrorStream()) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String buildOpenRoomBody(PublishConfig config, int hostPort, String updatedAt) {
        return "{" +
                "\"room_name\":\"" + jsonEscape(config.roomName) + "\"," +
                "\"host_name\":\"" + jsonEscape(config.hostName) + "\"," +
                "\"host_ip\":\"" + jsonEscape(config.hostIp) + "\"," +
                "\"host_port\":" + hostPort + "," +
                "\"status\":\"open\"," +
                "\"updated_at\":\"" + jsonEscape(updatedAt) + "\"" +
                appendRelayFieldsForOpenRoom(config) +
                appendNatFieldsForOpenRoom(config, hostPort) +
                "}";
    }

    private static String appendRelayFieldsForOpenRoom(PublishConfig config) {
        return ","
                + "\"relay_endpoint\":\"" + jsonEscape(config.relayEndpoint) + "\"," 
                + "\"relay_token\":\"" + jsonEscape(config.relayToken) + "\"," 
                + "\"relay_status\":\"" + jsonEscape(config.relayStatus) + "\"";
    }

    private static String appendNatFieldsForOpenRoom(PublishConfig config, int hostPort) {
        if (!Config.stunEnabled()) {
            return "";
        }

        String directEndpoint = config.hostIp + ":" + hostPort;
        Optional<String> stunPublicEndpoint = StunClient.resolvePublicEndpoint(Config.stunServer(), Config.stunTimeoutMs());
        String publicEndpoint = stunPublicEndpoint.orElse(directEndpoint);

        if (stunPublicEndpoint.isEmpty()) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] STUN endpoint resolve failed. Fallback to direct endpoint='{}'.",
                    directEndpoint
            );
        }

        String natMethod = publicEndpoint.equals(directEndpoint) ? "direct" : "stun";
        String candidates = "[{\"type\":\"direct\",\"endpoint\":\"" + jsonEscape(directEndpoint) + "\"}]";

        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] STUN publish fields prepared. nat_method='{}', public_endpoint='{}'",
                natMethod,
                publicEndpoint
        );

        return ","
                + "\"nat_method\":\"" + jsonEscape(natMethod) + "\","
                + "\"public_endpoint\":\"" + jsonEscape(publicEndpoint) + "\","
                + "\"candidates\":" + candidates;
    }


    private static PublishConfig loadPublishConfig() {
        String supabaseUrl = Config.supabaseUrl();
        String supabaseKey = Config.supabaseKey();
        String roomName = Config.roomName();
        String hostName = Config.publishHostName();
        String hostIp = Config.publishHostIp();
        String relayEndpoint = Config.relayPublishEndpoint();
        String relayToken = Config.relayToken();
        String relayStatus = Config.relayStatus();

        if (supabaseUrl.isBlank() || supabaseKey.isBlank() || roomName.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Skip room publish: supabase config is incomplete.");
            return null;
        }

        if (hostName.isBlank() || hostIp.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Skip room publish: set publish_host_name and publish_host_ip in config.");
            return null;
        }

        return new PublishConfig(supabaseUrl, supabaseKey, roomName, hostName, hostIp, relayEndpoint, relayToken, relayStatus);
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

    private record PublishConfig(
            String supabaseUrl,
            String supabaseKey,
            String roomName,
            String hostName,
            String hostIp,
            String relayEndpoint,
            String relayToken,
            String relayStatus
    ) {
    }

    private record RequestResult(boolean success, int statusCode, String body) {
    }
}
