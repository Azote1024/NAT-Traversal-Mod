package com.azote.nat_traversal_mod.net.supabase;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.net.RelayEndpoint;
import com.azote.nat_traversal_mod.net.StunClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class SupabaseRoomsPublisher {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    private SupabaseRoomsPublisher() {
    }

    public static void publishOpenRoom(int hostPort) {
        PublishConfig config = loadPublishConfig();
        if (config == null) {
            return;
        }

        if (hostPort < 1 || hostPort > 65535) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Skip room publish: invalid host port {}.", hostPort);
            return;
        }

        String endpoint = config.supabaseUrl + SupabaseApiPaths.ROOMS;
        String updatedAt = Instant.now().toString();
        NatClassification natClassification = classifyHostNat(config, hostPort);
        if (natClassification.publishHostIp().isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Skip room publish: no publishable host IP. Set publish.host_ip or enable working STUN.");
            return;
        }
        String body = buildOpenRoomBody(config, hostPort, updatedAt, natClassification);

        RequestResult result = sendJsonRequest(
                endpoint,
                "POST",
                body,
                config.supabaseKey,
                "resolution=merge-duplicates,return=minimal"
        );

        if (result.success) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] Room published. room_name='{}' -> {}:{}",
                    config.roomName,
                    natClassification.publishHostIp(),
                    hostPort
            );
            publishQuicSession(config, hostPort, updatedAt, natClassification);
            return;
        }

        NatTraversalMod.LOGGER.warn(
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
        String endpoint = config.supabaseUrl + SupabaseApiPaths.ROOMS + "?room_name=eq." + encodedRoomName;
        String updatedAt = Instant.now().toString();
        String body = new SupabaseJsonObjectBuilder()
                .addString("status", "closed")
                .addString("updated_at", updatedAt)
                .build();

        RequestResult result = sendJsonRequest(
                endpoint,
                "PATCH",
                body,
                config.supabaseKey,
                "return=minimal"
        );

        if (result.success) {
            NatTraversalMod.LOGGER.info("[nat-traversal-mod] Room closed. room_name='{}'", config.roomName);
            closeQuicSession(config, updatedAt);
            return;
        }

        NatTraversalMod.LOGGER.warn(
                "[nat-traversal-mod] Room close failed. status={}, body='{}'",
                result.statusCode,
                trimBody(result.body)
        );
    }

    private static RequestResult sendJsonRequest(String endpoint, String method, String body, String supabaseKey, String prefer) {
        try {
            HttpRequest request = SupabaseRestClient.buildAuthorizedJsonRequest(
                    endpoint,
                    method,
                    supabaseKey,
                    body,
                    REQUEST_TIMEOUT,
                    prefer
            );

            HttpResponse<String> response = SupabaseRestClient.sendString(request);
            int statusCode = response.statusCode();
            String responseBody = response.body();
            boolean success = SupabaseRestClient.classifyDefault(statusCode) == SupabaseRestClient.ResponseCategory.SUCCESS;
            return new RequestResult(success, statusCode, responseBody);
        } catch (IOException exception) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Room publish exception.", exception);
            return new RequestResult(false, -1, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Room publish interrupted.", exception);
            return new RequestResult(false, -1, exception.getMessage());
        } catch (RuntimeException exception) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Unexpected publish error.", exception);
            return new RequestResult(false, -1, exception.getMessage());
        }
    }

    private static String buildOpenRoomBody(PublishConfig config, int hostPort, String updatedAt, NatClassification natClassification) {
        String routeHint = "";
        return new SupabaseJsonObjectBuilder()
                .addString("room_name", config.roomName)
                .addString("host_name", config.hostName)
                .addString("host_ip", natClassification.publishHostIp())
                .addNumber("host_port", hostPort)
                .addString("status", "open")
                .addString("updated_at", updatedAt)
                .addString("host_nat_type", natClassification.hostNatType())
                .addString("nat_confidence", natClassification.natConfidence())
                .addString("nat_classified_at", updatedAt)
                .addString("route_hint", routeHint)
                .addString("relay_endpoint", config.relayEndpoint)
                .addString("relay_token", config.relayToken)
                .addString("relay_status", config.relayStatus)
                .addString("nat_method", natClassification.natMethod())
                .addString("public_endpoint", natClassification.publicEndpoint())
                .addRawJson("candidates", buildCandidatesJson(config, hostPort, natClassification.publishHostIp()))
                .build();
    }

    private static String buildCandidatesJson(PublishConfig config, int hostPort, String publishHostIp) {
        String directEndpoint = publishHostIp + ":" + hostPort;
        return new SupabaseJsonObjectBuilder()
                .addString("direct_endpoint", directEndpoint)
                .addString("quic_endpoint", config.quicEndpoint)
                .addString("quic_status", config.quicStatus)
                .addNumber("quic_attempts", config.quicAttempts)
                .addNumber("quic_attempt_interval_ms", config.quicAttemptIntervalMs)
                .build();
    }


    private static PublishConfig loadPublishConfig() {
        PublishConfigSnapshot snapshot = PublishConfigSnapshot.capture();

        String supabaseUrl = snapshot.supabaseUrl();
        String supabaseKey = snapshot.supabaseKey();
        String roomName = snapshot.roomName();
        String hostName = snapshot.hostName();
        String hostIp = snapshot.hostIp();
        String relayEndpoint = snapshot.relayEndpoint();
        String relayToken = snapshot.relayToken();
        String relayStatus = snapshot.relayStatus();
        String quicEndpoint = snapshot.quicEndpoint();
        String quicStatus = snapshot.quicStatus();

        if (supabaseUrl.isBlank() || supabaseKey.isBlank() || roomName.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Skip room publish: supabase config is incomplete.");
            return null;
        }

        if (hostName.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Skip room publish: set publish.host_name in config.");
            return null;
        }

        if (hostIp.isBlank() && !snapshot.stunEnabled()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Skip room publish: set publish.host_ip in config or enable STUN.");
            return null;
        }

        return new PublishConfig(
                supabaseUrl,
                supabaseKey,
                roomName,
                hostName,
                hostIp,
                relayEndpoint,
                relayToken,
                relayStatus,
                quicEndpoint,
                quicStatus,
                snapshot.stunEnabled(),
                snapshot.stunServer(),
                snapshot.stunTimeoutMs(),
                snapshot.quicAttempts(),
                snapshot.quicAttemptIntervalMs()
        );
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
        String endpoint = config.supabaseUrl + SupabaseApiPaths.QUIC_SESSIONS;
        String defaultPunchEndpoint = initialQuicPunchEndpoint(config, hostPort, natClassification);
        String punchToken = buildPunchToken(config.roomName, updatedAt);
        String routeDecision = "quic_try";
        String relayReason = "";
        String body = new SupabaseJsonObjectBuilder()
                .addString("room_name", config.roomName)
                .addString("quic_endpoint", config.quicEndpoint)
                .addString("quic_status", config.quicStatus)
                .addString("punch_endpoint", defaultPunchEndpoint)
                .addString("punch_status", "ready")
                .addString("punch_token", punchToken)
                .addString("host_nat_type", natClassification.hostNatType())
                .addString("route_decision", routeDecision)
                .addString("route_decided_at", updatedAt)
                .addString("relay_reason", relayReason)
                .addString("status", "open")
                .addString("updated_at", updatedAt)
                .build();

        RequestResult result = sendJsonRequest(
                endpoint,
                "POST",
                body,
                config.supabaseKey,
                "resolution=merge-duplicates,return=minimal"
        );

        if (!result.success) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] quic_sessions publish failed. status={}, room_name='{}'.",
                    result.statusCode,
                    config.roomName
            );
        }
    }

    private static void closeQuicSession(PublishConfig config, String updatedAt) {
        String encodedRoomName = URLEncoder.encode(config.roomName, StandardCharsets.UTF_8);
        String endpoint = config.supabaseUrl + SupabaseApiPaths.QUIC_SESSIONS + "?room_name=eq." + encodedRoomName;
        String body = new SupabaseJsonObjectBuilder()
                .addString("status", "closed")
                .addString("quic_status", "down")
                .addString("punch_status", "idle")
                .addString("punch_token", "")
                .addString("route_decision", "")
                .addString("relay_reason", "")
                .addString("updated_at", updatedAt)
                .build();

        RequestResult result = sendJsonRequest(
                endpoint,
                "PATCH",
                body,
                config.supabaseKey,
                "return=minimal"
        );

        if (!result.success) {
            NatTraversalMod.LOGGER.info(
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
            String quicStatus,
            boolean stunEnabled,
            String stunServer,
            int stunTimeoutMs,
            int quicAttempts,
            int quicAttemptIntervalMs
    ) {
    }

    private record RequestResult(boolean success, int statusCode, String body) {
    }

    private static String buildPunchToken(String roomName, String updatedAt) {
        return roomName + "-" + updatedAt.replace(":", "").replace("-", "").replace(".", "");
    }

    private static String initialQuicPunchEndpoint(PublishConfig config, int hostPort, NatClassification natClassification) {
        Optional<RelayEndpoint> quicEndpoint = RelayEndpoint.parse(config.quicEndpoint, "quic.publish_endpoint");
        if (quicEndpoint.isPresent()) {
            String host = natClassification.publishHostIp().isBlank()
                    ? quicEndpoint.get().host()
                    : natClassification.publishHostIp();
            return host + ":" + quicEndpoint.get().port();
        }

        if (!natClassification.publicEndpoint().isBlank()) {
            return natClassification.publicEndpoint();
        }

        return natClassification.publishHostIp() + ":" + hostPort;
    }

    private static NatClassification classifyHostNat(PublishConfig config, int hostPort) {
        String publishHostIp = config.hostIp;
        String directEndpoint = publishHostIp.isBlank() ? "" : publishHostIp + ":" + hostPort;
        if (!config.stunEnabled) {
            return new NatClassification("unknown", "", "direct", directEndpoint, publishHostIp);
        }

        Optional<String> stunPublicEndpoint = StunClient.resolvePublicEndpoint(config.stunServer, config.stunTimeoutMs);
        String publicEndpoint = stunPublicEndpoint.orElse(directEndpoint);
        if (stunPublicEndpoint.isPresent()) {
            Optional<RelayEndpoint> parsed = RelayEndpoint.parse(stunPublicEndpoint.get(), "stun.public_endpoint");
            if (parsed.isPresent()) {
                String stunHost = parsed.get().host();
                if (!stunHost.isBlank() && !stunHost.equalsIgnoreCase(publishHostIp)) {
                    NatTraversalMod.LOGGER.info(
                            "[nat-traversal-mod] STUN public IP resolved. override host_ip from '{}' to '{}'.",
                            publishHostIp,
                            stunHost
                    );
                    publishHostIp = stunHost;
                }
            }
        }

        if (stunPublicEndpoint.isEmpty()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] STUN endpoint resolve failed. Fallback to direct endpoint='{}'.",
                    directEndpoint.isBlank() ? "<empty>" : directEndpoint
            );
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] STUN publish fields prepared. nat_method='{}', public_endpoint='{}'",
                    "direct",
                    publicEndpoint
            );
            return new NatClassification("unknown", "", "direct", publicEndpoint, publishHostIp);
        }

        String natMethod = publicEndpoint.equals(directEndpoint) ? "direct" : "stun";
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] STUN publish fields prepared. nat_method='{}', public_endpoint='{}'",
                natMethod,
                publicEndpoint
        );
        return new NatClassification("unknown", "", natMethod, publicEndpoint, publishHostIp);
    }

    private record NatClassification(String hostNatType, String natConfidence, String natMethod, String publicEndpoint, String publishHostIp) {
    }
}

