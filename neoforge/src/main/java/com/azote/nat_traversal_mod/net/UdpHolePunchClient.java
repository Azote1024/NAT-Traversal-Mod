package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

final class UdpHolePunchClient {
    private UdpHolePunchClient() {
    }

    static boolean oneShotPunch(RelayEndpoint endpoint, String roomName, String token, int attempts, int intervalMs) {
        if (attempts < 1) {
            return false;
        }

        byte[] payload = ("NAT-PUNCH " + roomName + " " + token).getBytes(StandardCharsets.UTF_8);
        InetSocketAddress remote = new InetSocketAddress(endpoint.host(), endpoint.port());

        try (DatagramSocket socket = new DatagramSocket()) {
            for (int i = 0; i < attempts; i++) {
                DatagramPacket packet = new DatagramPacket(payload, payload.length, remote);
                socket.send(packet);
                if (i + 1 < attempts) {
                    Thread.sleep(intervalMs);
                }
            }
            return true;
        } catch (Exception exception) {
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] UDP hole punch failed. room_name='{}', endpoint='{}:{}'",
                    roomName,
                    endpoint.host(),
                    endpoint.port(),
                    exception
            );
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}


