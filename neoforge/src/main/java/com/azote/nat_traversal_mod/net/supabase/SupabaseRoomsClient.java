package com.azote.nat_traversal_mod.net.supabase;

import com.azote.nat_traversal_mod.config.runtime.ConnectStrategy;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.net.QuicP2pManager;
import com.azote.nat_traversal_mod.net.ResolvedTarget;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class SupabaseRoomsClient {
    private static final long ROOM_FRESHNESS_TTL_MILLIS = 180_000L;

    private SupabaseRoomsClient() {
    }

    public static Optional<ResolvedTarget> resolve() {
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        String supabaseUrl = runtimeConfig.supabaseUrl();
        String supabaseKey = runtimeConfig.supabaseApiKey();
        String roomName = runtimeConfig.roomName();

        if (supabaseUrl.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] supabase.url is empty. Fallback to original target.");
            return Optional.empty();
        }

        if (supabaseKey.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] supabase.api_key is empty. Fallback to original target.");
            return Optional.empty();
        }

        if (roomName.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] mode.room_name is empty. Fallback to original target.");
            return Optional.empty();
        }

        Optional<String> roomBody = SupabaseRoomsApiClient.fetchOpenRoomBody(supabaseUrl, supabaseKey, roomName);
        if (roomBody.isEmpty()) {
            return Optional.empty();
        }

        Optional<RoomSnapshotParser.RoomSnapshot> snapshot = RoomSnapshotParser.parse(roomBody.get(), roomName);
        if (snapshot.isEmpty()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] Room not found or invalid data. mode.room_name='{}'. Fallback to original target.",
                    roomName
            );
            return Optional.empty();
        }

        return resolveFromSnapshot(snapshot.get(), roomName, runtimeConfig);
    }

    private static Optional<ResolvedTarget> resolveFromSnapshot(
            RoomSnapshotParser.RoomSnapshot snapshot,
            String roomName,
            RuntimeConfigSnapshot runtimeConfig
    ) {
        String body = snapshot.rawBody();

        long ageMillis = Duration.between(snapshot.updatedAt(), Instant.now()).toMillis();
        boolean isFresh = ageMillis >= 0L && ageMillis <= ROOM_FRESHNESS_TTL_MILLIS;
        ConnectStrategy strategy = runtimeConfig.connectStrategy();
        boolean quicCapable = strategy == ConnectStrategy.QUIC_FIRST || strategy == ConnectStrategy.TCP_QUIC_RELAY;
        if (!isFresh) {
            if (!quicCapable) {
                NatTraversalMod.LOGGER.warn(
                        "[nat-traversal-mod] Room data is stale. room_name='{}', age_ms={}, ttl_ms={}. Fallback to original target.",
                        roomName,
                        ageMillis,
                        ROOM_FRESHNESS_TTL_MILLIS
                );
                return Optional.empty();
            }

            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] Room data is stale but QUIC-capable mode allows QUIC route attempt. room_name='{}', age_ms={}, ttl_ms={}",
                    roomName,
                    ageMillis,
                    ROOM_FRESHNESS_TTL_MILLIS
            );
        }

        if (isFresh) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] Room data is fresh. room_name='{}', age_ms={}, ttl_ms={}",
                    roomName,
                    ageMillis,
                    ROOM_FRESHNESS_TTL_MILLIS
            );
        }

        if (quicCapable) {
            SupabaseQuicSessionClient.markRouteDecisionQuicTry(roomName);
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

            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] QUIC route unavailable. room_name='{}'. Try relay/public fallback.",
                    roomName
            );
            Optional<ResolvedTarget> fallbackTarget = resolveRelayThenPublic(snapshot, roomName, runtimeConfig);
            if (fallbackTarget.isPresent()) {
                return fallbackTarget;
            }
        } else if (strategy == ConnectStrategy.RELAY_FIRST) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] mode.connect_strategy=relay_first. Try relay endpoint before public_endpoint. mode.room_name='{}'.",
                    roomName
            );
            Optional<ResolvedTarget> relayFirstTarget = resolveRelayThenPublic(snapshot, roomName, runtimeConfig);
            if (relayFirstTarget.isPresent()) {
                return relayFirstTarget;
            }
        } else {
            Optional<ResolvedTarget> publicFirstTarget = resolvePublicThenRelay(snapshot, roomName, runtimeConfig);
            if (publicFirstTarget.isPresent()) {
                return publicFirstTarget;
            }
        }

        if (runtimeConfig.stunEnabled()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] public_endpoint is not used. room_name='{}'. Fallback to host_ip:host_port.",
                    roomName
            );
        }

        return Optional.of(new ResolvedTarget(snapshot.hostIp(), snapshot.hostPort()));
    }

    private static Optional<ResolvedTarget> resolveRelayThenPublic(
            RoomSnapshotParser.RoomSnapshot snapshot,
            String roomName,
            RuntimeConfigSnapshot runtimeConfig
    ) {
        Optional<ResolvedTarget> relayEndpointTarget = RoomRouteSelector.tryRelayEndpoint(snapshot, roomName, runtimeConfig);
        if (relayEndpointTarget.isPresent()) {
            return relayEndpointTarget;
        }
        return RoomRouteSelector.tryPublicEndpoint(snapshot, roomName, runtimeConfig);
    }

    private static Optional<ResolvedTarget> resolvePublicThenRelay(
            RoomSnapshotParser.RoomSnapshot snapshot,
            String roomName,
            RuntimeConfigSnapshot runtimeConfig
    ) {
        Optional<ResolvedTarget> publicEndpointTarget = RoomRouteSelector.tryPublicEndpoint(snapshot, roomName, runtimeConfig);
        if (publicEndpointTarget.isPresent()) {
            return publicEndpointTarget;
        }
        return RoomRouteSelector.tryRelayEndpoint(snapshot, roomName, runtimeConfig);
    }

}


