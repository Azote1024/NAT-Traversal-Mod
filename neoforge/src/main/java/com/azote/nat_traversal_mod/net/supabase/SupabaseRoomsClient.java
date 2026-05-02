package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.NatTraversalMod;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class SupabaseRoomsClient {
    private static final long ROOM_FRESHNESS_TTL_MILLIS = 180_000L;

    private SupabaseRoomsClient() {
    }

    public static Optional<ResolvedTarget> resolve() {
        String supabaseUrl = Config.supabaseUrl();
        String supabaseKey = Config.supabaseKey();
        String roomName = Config.roomName();

        if (supabaseUrl.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] supabase_url is empty. Fallback to original target.");
            return Optional.empty();
        }

        if (supabaseKey.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] supabase_key is empty. Fallback to original target.");
            return Optional.empty();
        }

        if (roomName.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] room_name is empty. Fallback to original target.");
            return Optional.empty();
        }

        Optional<String> roomBody = SupabaseRoomsApiClient.fetchOpenRoomBody(supabaseUrl, supabaseKey, roomName);
        if (roomBody.isEmpty()) {
            return Optional.empty();
        }

        Optional<RoomSnapshotParser.RoomSnapshot> snapshot = RoomSnapshotParser.parse(roomBody.get(), roomName);
        if (snapshot.isEmpty()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] Room not found or invalid data. room_name='{}'. Fallback to original target.",
                    roomName
            );
            return Optional.empty();
        }

        return resolveFromSnapshot(snapshot.get(), roomName);
    }

    private static Optional<ResolvedTarget> resolveFromSnapshot(RoomSnapshotParser.RoomSnapshot snapshot, String roomName) {
        String body = snapshot.rawBody();

        long ageMillis = Duration.between(snapshot.updatedAt(), Instant.now()).toMillis();
        boolean isFresh = ageMillis >= 0L && ageMillis <= ROOM_FRESHNESS_TTL_MILLIS;
        if (!isFresh) {
            if (!Config.quicFirstMode() && !Config.tcpQuicRelayMode()) {
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

        if (Config.quicFirstMode() || Config.tcpQuicRelayMode()) {
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
            Optional<ResolvedTarget> fallbackTarget = resolveRelayThenPublic(snapshot, roomName);
            if (fallbackTarget.isPresent()) {
                return fallbackTarget;
            }
        } else if (Config.relayFirstMode()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] relay_priority_mode=relay_first. Try relay endpoint before public_endpoint. room_name='{}'.",
                    roomName
            );
            Optional<ResolvedTarget> relayFirstTarget = resolveRelayThenPublic(snapshot, roomName);
            if (relayFirstTarget.isPresent()) {
                return relayFirstTarget;
            }
        } else {
            Optional<ResolvedTarget> publicFirstTarget = resolvePublicThenRelay(snapshot, roomName);
            if (publicFirstTarget.isPresent()) {
                return publicFirstTarget;
            }
        }

        if (Config.stunEnabled()) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] public_endpoint is not used. room_name='{}'. Fallback to host_ip:host_port.",
                    roomName
            );
        }

        return Optional.of(new ResolvedTarget(snapshot.hostIp(), snapshot.hostPort()));
    }

    private static Optional<ResolvedTarget> resolveRelayThenPublic(RoomSnapshotParser.RoomSnapshot snapshot, String roomName) {
        Optional<ResolvedTarget> relayEndpointTarget = RoomRouteSelector.tryRelayEndpoint(snapshot, roomName);
        if (relayEndpointTarget.isPresent()) {
            return relayEndpointTarget;
        }
        return RoomRouteSelector.tryPublicEndpoint(snapshot, roomName);
    }

    private static Optional<ResolvedTarget> resolvePublicThenRelay(RoomSnapshotParser.RoomSnapshot snapshot, String roomName) {
        Optional<ResolvedTarget> publicEndpointTarget = RoomRouteSelector.tryPublicEndpoint(snapshot, roomName);
        if (publicEndpointTarget.isPresent()) {
            return publicEndpointTarget;
        }
        return RoomRouteSelector.tryRelayEndpoint(snapshot, roomName);
    }

}


