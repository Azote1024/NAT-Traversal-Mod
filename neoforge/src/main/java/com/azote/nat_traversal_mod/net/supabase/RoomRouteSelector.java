package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.NatTraversalMod;

import java.util.Optional;

final class RoomRouteSelector {
    private RoomRouteSelector() {
    }

    static Optional<ResolvedTarget> tryRelayEndpoint(RoomSnapshotParser.RoomSnapshot snapshot, String roomName) {
        if (!Config.relayClientConnectorEnabled()) {
            return Optional.empty();
        }

        Optional<String> relayStatus = snapshot.relayStatus();
        if (relayStatus.isEmpty()) {
            return Optional.empty();
        }

        String status = relayStatus.get();
        if (!"ready".equalsIgnoreCase(status)) {
            if (!status.isEmpty()) {
                NatTraversalMod.LOGGER.info(
                        "[nat-traversal-mod] relay_status is not ready. room_name='{}', relay_status='{}'.",
                        roomName,
                        status
                );
            }
            return Optional.empty();
        }

        Optional<String> relayEndpoint = snapshot.relayEndpoint();
        if (relayEndpoint.isEmpty()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] relay_status=ready but relay_endpoint is missing. room_name='{}'.",
                    roomName
            );
            return Optional.empty();
        }

        if (relayEndpoint.get().isBlank()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] relay_status=ready but relay_endpoint is empty. room_name='{}'.",
                    roomName
            );
            return Optional.empty();
        }

        if (!RelayClientConnectorManager.ensureStarted()) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] relay_status=ready but local relay client connector is not running. room_name='{}'.",
                    roomName
            );
            return Optional.empty();
        }

        int localRelayPort = Config.relayClientLocalPort();
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] Use local relay client connector. room_name='{}', relay_endpoint='{}', target='127.0.0.1:{}'",
                roomName,
                relayEndpoint.get(),
                localRelayPort
        );
        return Optional.of(new ResolvedTarget("127.0.0.1", localRelayPort));
    }

    static Optional<ResolvedTarget> tryPublicEndpoint(RoomSnapshotParser.RoomSnapshot snapshot, String roomName) {
        Optional<String> publicEndpoint = snapshot.publicEndpoint();
        if (publicEndpoint.isEmpty()) {
            if (Config.stunEnabled()) {
                NatTraversalMod.LOGGER.info(
                        "[nat-traversal-mod] public_endpoint key is missing in room payload. room_name='{}'.",
                        roomName
                );
            }
            return Optional.empty();
        }

        if (publicEndpoint.get().isEmpty()) {
            if (Config.stunEnabled()) {
                NatTraversalMod.LOGGER.info(
                        "[nat-traversal-mod] public_endpoint is empty. room_name='{}'.",
                        roomName
                );
            }
            return Optional.empty();
        }

        Optional<RelayEndpoint> parsed = RelayEndpoint.parse(publicEndpoint.get(), "public_endpoint (room_name='" + roomName + "')");
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        ResolvedTarget target = new ResolvedTarget(parsed.get().host(), parsed.get().port());
        NatTraversalMod.LOGGER.info(
                "[nat-traversal-mod] Use public_endpoint from room. room_name='{}', target='{}:{}'",
                roomName,
                target.hostIp(),
                target.hostPort()
        );
        return Optional.of(target);
    }
}


