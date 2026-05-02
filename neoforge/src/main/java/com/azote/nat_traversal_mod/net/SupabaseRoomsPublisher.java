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
        NatClassification natClassification = classifyHostNat(config, hostPort);
        String body = buildOpenRoomBody(config, hostPort, updatedAt, natClassification);

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
            publishQuicSession(config, hostPort, updatedAt, natClassification);
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
            closeQuicSession(config, updatedAt);
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

    private static String buildOpenRoomBody(PublishConfig config, int hostPort, String updatedAt, NatClassification natClassification) {
        return "{" +
                "\"room_name\":\"" + jsonEscape(config.roomName) + "\"," +
                "\"host_name\":\"" + jsonEscape(config.hostName) + "\"," +
                "\"host_ip\":\"" + jsonEscape(config.hostIp) + "\"," +
                "\"host_port\":" + hostPort + "," +
                "\"status\":\"open\"," +
                "\"updated_at\":\"" + jsonEscape(updatedAt) + "\"" +
                appendNatRoutingFields(natClassification, updatedAt) +
                appendRelayFieldsForOpenRoom(config) +
                appendNatFieldsForOpenRoom(natClassification) +
                appendCandidatesForOpenRoom(config, hostPort) +
                "}";
    }

    private static String appendRelayFieldsForOpenRoom(PublishConfig config) {
        return ","
                + "\"relay_endpoint\":\"" + jsonEscape(config.relayEndpoint) + "\"," 
                + "\"relay_token\":\"" + jsonEscape(config.relayToken) + "\"," 
                + "\"relay_status\":\"" + jsonEscape(config.relayStatus) + "\"";
    }

    private static String appendNatFieldsForOpenRoom(NatClassification natClassification) {
        return ","
                + "\"nat_method\":\"" + jsonEscape(natClassification.natMethod()) + "\","
                + "\"public_endpoint\":\"" + jsonEscape(natClassification.publicEndpoint()) + "\"";
    }

    private static String appendNatRoutingFields(NatClassification natClassification, String updatedAt) {
        String routeHint = "";
        return ","
                + "\"host_nat_type\":\"" + jsonEscape(natClassification.hostNatType()) + "\","
                + "\"nat_confidence\":\"" + jsonEscape(natClassification.natConfidence()) + "\","
                + "\"nat_classified_at\":\"" + jsonEscape(updatedAt) + "\","
                + "\"route_hint\":\"" + jsonEscape(routeHint) + "\"";
    }

    private static String appendCandidatesForOpenRoom(PublishConfig config, int hostPort) {
        String directEndpoint = config.hostIp + ":" + hostPort;
        String quicEndpoint = config.quicEndpoint;
        String quicStatus = config.quicStatus;

        String candidates = "{" +
                "\"direct_endpoint\":\"" + jsonEscape(directEndpoint) + "\"," +
                "\"quic_endpoint\":\"" + jsonEscape(quicEndpoint) + "\"," +
                "\"quic_status\":\"" + jsonEscape(quicStatus) + "\"," +
                "\"quic_attempts\":" + Config.quicAttempts() + "," +
                "\"quic_attempt_interval_ms\":" + Config.quicAttemptIntervalMs() +
                "}";

        return ",\"candidates\":" + candidates;
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
        String quicEndpoint = Config.quicPublishEndpoint();
        String quicStatus = Config.quicStatus();

        if (supabaseUrl.isBlank() || supabaseKey.isBlank() || roomName.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Skip room publish: supabase config is incomplete.");
            return null;
        }

        if (hostName.isBlank() || hostIp.isBlank()) {
            Nat_traversal_mod.LOGGER.warn("[nat-traversal-mod] Skip room publish: set publish_host_name and publish_host_ip in config.");
            return null;
        }

        return new PublishConfig(supabaseUrl, supabaseKey, roomName, hostName, hostIp, relayEndpoint, relayToken, relayStatus, quicEndpoint, quicStatus);
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

    private static void publishQuicSession(PublishConfig config, int hostPort, String updatedAt, NatClassification natClassification) {
        String endpoint = config.supabaseUrl + "/rest/v1/quic_sessions";
        String defaultPunchEndpoint = config.hostIp + ":" + hostPort;
        String punchToken = buildPunchToken(config.roomName, updatedAt);
        String routeDecision = "quic_try";
        String relayReason = "";
        String body = "{"
                + "\"room_name\":\"" + jsonEscape(config.roomName) + "\"," 
                + "\"quic_endpoint\":\"" + jsonEscape(config.quicEndpoint) + "\"," 
                + "\"quic_status\":\"" + jsonEscape(config.quicStatus) + "\"," 
                + "\"punch_endpoint\":\"" + jsonEscape(defaultPunchEndpoint) + "\"," 
                + "\"punch_status\":\"ready\","
                + "\"punch_token\":\"" + jsonEscape(punchToken) + "\","
                + "\"host_nat_type\":\"" + jsonEscape(natClassification.hostNatType()) + "\","
                + "\"route_decision\":\"" + jsonEscape(routeDecision) + "\","
                + "\"route_decided_at\":\"" + jsonEscape(updatedAt) + "\","
                + "\"relay_reason\":\"" + jsonEscape(relayReason) + "\","
                + "\"status\":\"open\","
                + "\"updated_at\":\"" + jsonEscape(updatedAt) + "\""
                + "}";

        RequestResult result = sendJsonRequest(
                endpoint,
                "POST",
                body,
                config.supabaseKey,
                "resolution=merge-duplicates,return=minimal"
        );

        if (!result.success) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] quic_sessions publish failed. status={}, room_name='{}'.",
                    result.statusCode,
                    config.roomName
            );
        }
    }

    private static void closeQuicSession(PublishConfig config, String updatedAt) {
        String encodedRoomName = URLEncoder.encode(config.roomName, StandardCharsets.UTF_8);
        String endpoint = config.supabaseUrl + "/rest/v1/quic_sessions?room_name=eq." + encodedRoomName;
        String body = "{"
                + "\"status\":\"closed\"," 
                + "\"quic_status\":\"down\"," 
                + "\"punch_status\":\"idle\","
                + "\"punch_token\":\"\","
                + "\"route_decision\":\"\","
                + "\"relay_reason\":\"\","
                + "\"updated_at\":\"" + jsonEscape(updatedAt) + "\""
                + "}";

        RequestResult result = sendJsonRequest(
                endpoint,
                "PATCH",
                body,
                config.supabaseKey,
                "return=minimal"
        );

        if (!result.success) {
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] quic_sessions close failed. status={}, room_name='{}'.",
                    result.statusCode,
                    config.roomName
            );
        }
    }

    private record PublishConfig(
            String supabaseUrl,
            String supabaseKey,
            String roomName,
            String hostName,
            String hostIp,
            String relayEndpoint,
            String relayToken,
            String relayStatus,
            String quicEndpoint,
            String quicStatus
    ) {
    }

    private record RequestResult(boolean success, int statusCode, String body) {
    }

    private static String buildPunchToken(String roomName, String updatedAt) {
        return roomName + "-" + updatedAt.replace(":", "").replace("-", "").replace(".", "");
    }

    private static NatClassification classifyHostNat(PublishConfig config, int hostPort) {
        String directEndpoint = config.hostIp + ":" + hostPort;
        if (!Config.stunEnabled()) {
            return new NatClassification("unknown", "", "direct", directEndpoint);
        }

        Optional<String> stunPublicEndpoint = StunClient.resolvePublicEndpoint(Config.stunServer(), Config.stunTimeoutMs());
        String publicEndpoint = stunPublicEndpoint.orElse(directEndpoint);
        if (stunPublicEndpoint.isEmpty()) {
            Nat_traversal_mod.LOGGER.warn(
                    "[nat-traversal-mod] STUN endpoint resolve failed. Fallback to direct endpoint='{}'.",
                    directEndpoint
            );
            Nat_traversal_mod.LOGGER.info(
                    "[nat-traversal-mod] STUN publish fields prepared. nat_method='{}', public_endpoint='{}'",
                    "direct",
                    publicEndpoint
            );
            return new NatClassification("unknown", "", "direct", publicEndpoint);
        }

        String natMethod = publicEndpoint.equals(directEndpoint) ? "direct" : "stun";
        Nat_traversal_mod.LOGGER.info(
                "[nat-traversal-mod] STUN publish fields prepared. nat_method='{}', public_endpoint='{}'",
                natMethod,
                publicEndpoint
        );
        return new NatClassification("unknown", "", natMethod, publicEndpoint);
    }

    private record NatClassification(String hostNatType, String natConfidence, String natMethod, String publicEndpoint) {
    }
}
