package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.net.routing.QuicDirectRouteContext;
import java.util.Optional;
import java.util.UUID;

/**
 * First implementation slice for QUIC-first routing.
 *
 * This manager currently handles room payload parsing and controlled multi-attempt flow.
 * Transport establishment is intentionally minimal and returns empty when no direct hint exists.
 */
public final class QuicP2pManager {
    enum QuicRouteState {
        DISABLED,
        HINT_MISSING,
        NOT_READY,
        INVALID_ENDPOINT,
        BLOCKED_BY_RELAY_ENDPOINT,
        TRANSPORT_UNAVAILABLE,
        ATTEMPTING,
        ESTABLISHED,
        EXHAUSTED
    }

    private static final QuicTransport TRANSPORT = createTransport();
    private static final String CLIENT_KEY = UUID.randomUUID().toString();

    private QuicP2pManager() {
    }

    static String clientKey() {
        return CLIENT_KEY;
    }

    private static QuicTransport createTransport() {
        try {
            if (!QuicRuntimeClasspath.ensureAvailable()) {
                throw new IllegalStateException("QUIC runtime classes are unavailable");
            }
            return new NettyQuicTransport();
        } catch (Throwable throwable) {
            NatTraversalMod.LOGGER.info("[nat-traversal-mod] QUIC transport backend is unavailable. Fallback to relay/public routes.");
            return new NoopQuicTransport();
        }
    }

    public static Optional<ResolvedTarget> tryResolveFromRoom(String body, String roomName) {
        Optional<String> endpoint = QuicRouteHintDecider.decideEndpointFromRoomBody(body, roomName);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }
        return tryActivateEndpoint(endpoint.get(), body, roomName);
    }

    public static Optional<ResolvedTarget> tryResolveFromSessionBody(String body, String roomName) {
        Optional<String> endpoint = QuicRouteHintDecider.decideEndpointFromSessionBody(body, roomName);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }
        return tryActivateEndpoint(endpoint.get(), body, roomName);
    }

    private static Optional<ResolvedTarget> tryActivateEndpoint(String endpoint, String roomBody, String roomName) {
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        Optional<RelayEndpoint> parsedEndpoint = RelayEndpoint.parse(endpoint, "quic_endpoint");
        if (parsedEndpoint.isEmpty()) {
            logState(roomName, QuicRouteState.INVALID_ENDPOINT, "invalid quic_endpoint=" + endpoint);
            return Optional.empty();
        }
        RelayEndpoint quicEndpoint = parsedEndpoint.get();
        String attemptId = QuicAttemptRecorder.startAttempt(roomName, CLIENT_KEY);
        QuicHolePunchCoordinator.prepareOneShotPunch(roomBody, roomName, quicEndpoint, attemptId, CLIENT_KEY);

        if (QuicDirectConnectorFactory.isOperational()) {
            QuicDirectRouteContext.set(quicEndpoint, attemptId);
            logState(roomName, QuicRouteState.ESTABLISHED, "direct connector prepared");
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] QUIC direct route prepared. room_name='{}', attempt_id='{}', endpoint='{}:{}'",
                    roomName,
                    attemptId,
                    quicEndpoint.host(),
                    quicEndpoint.port()
            );
            return Optional.of(new ResolvedTarget(quicEndpoint.host(), quicEndpoint.port()));
        }

        if (!TRANSPORT.isOperational()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] QUIC transport is not operational yet. room_name='{}'. Fallback to next route.",
                    roomName
            );
            logState(roomName, QuicRouteState.TRANSPORT_UNAVAILABLE, "transport unavailable");
            return Optional.empty();
        }

        int attempts = runtimeConfig.quicAttempts();
        int intervalMs = runtimeConfig.quicAttemptIntervalMs();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            logState(roomName, QuicRouteState.ATTEMPTING, "attempt=" + attempt + "/" + attempts);
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] QUIC attempt {}/{} planned. room_name='{}', endpoint='{}', tls_mode='{}'.",
                    attempt,
                    attempts,
                    roomName,
                    endpoint,
                    runtimeConfig.quicTlsMode()
            );

            Optional<ResolvedTarget> localTarget = TRANSPORT.tryActivate(quicEndpoint, roomName);
            if (localTarget.isPresent()) {
                logState(roomName, QuicRouteState.ESTABLISHED, "local target selected");
                NatTraversalMod.LOGGER.info(
                        "[nat-traversal-mod] QUIC route selected. room_name='{}', target='{}:{}'",
                        roomName,
                        localTarget.get().hostIp(),
                        localTarget.get().hostPort()
                );
                return localTarget;
            }

            if (attempt < attempts) {
                sleepQuietly(intervalMs);
            }
        }

        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] QUIC attempts exhausted for room_name='{}'. Fallback to next route.",
                roomName
        );
        logState(roomName, QuicRouteState.EXHAUSTED, "all attempts failed");
        return Optional.empty();
    }

    static void logState(String roomName, QuicRouteState state, String detail) {
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] QUIC route state={}, room_name='{}', detail='{}'",
                state,
                roomName,
                detail
        );
    }


    private static void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}



