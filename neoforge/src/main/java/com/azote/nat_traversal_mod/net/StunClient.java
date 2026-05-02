package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.UnresolvedAddressException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

public final class StunClient {
    private static final int STUN_BINDING_REQUEST = 0x0001;
    private static final int STUN_BINDING_RESPONSE = 0x0101;
    private static final int STUN_MAGIC_COOKIE = 0x2112A442;
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    private static final SecureRandom RANDOM = new SecureRandom();

    private StunClient() {
    }

    public static Optional<String> resolvePublicEndpoint(String stunServer, int timeoutMs) {
        ServerSpec serverSpec = parseServerSpec(stunServer);
        if (serverSpec == null) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Invalid stun_server='{}'.", stunServer);
            return Optional.empty();
        }

        byte[] txId = new byte[12];
        RANDOM.nextBytes(txId);
        byte[] request = buildBindingRequest(txId);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(Math.max(500, timeoutMs));
            socket.connect(new InetSocketAddress(serverSpec.host, serverSpec.port));

            DatagramPacket requestPacket = new DatagramPacket(request, request.length);
            socket.send(requestPacket);

            byte[] buffer = new byte[512];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);

            return parseBindingResponse(buffer, responsePacket.getLength(), txId);
        } catch (IOException exception) {
            if (isUnresolvedAddress(exception)) {
                NatTraversalMod.LOGGER.warn("[nat-traversal-mod] STUN request failed: unresolved address. stun_server='{}'", stunServer);
                return Optional.empty();
            }

            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] STUN request failed. stun_server='{}'", stunServer, exception);
            return Optional.empty();
        }
    }

    private static boolean isUnresolvedAddress(IOException exception) {
        if (exception.getMessage() != null && exception.getMessage().contains("Unresolved address")) {
            return true;
        }
        return exception.getCause() instanceof UnresolvedAddressException;
    }

    private static byte[] buildBindingRequest(byte[] txId) {
        byte[] request = new byte[20];
        writeU16(request, 0, STUN_BINDING_REQUEST);
        writeU16(request, 2, 0);
        writeMagicCookie(request);
        System.arraycopy(txId, 0, request, 8, txId.length);
        return request;
    }

    private static Optional<String> parseBindingResponse(byte[] packet, int length, byte[] expectedTxId) {
        if (length < 20) {
            return Optional.empty();
        }

        int messageType = readU16(packet, 0);
        int magicCookie = readMagicCookie(packet);
        byte[] txId = Arrays.copyOfRange(packet, 8, 20);
        if (messageType != STUN_BINDING_RESPONSE || magicCookie != STUN_MAGIC_COOKIE || !Arrays.equals(txId, expectedTxId)) {
            return Optional.empty();
        }

        int offset = 20;
        while (offset + 4 <= length) {
            int attrType = readU16(packet, offset);
            int attrLength = readU16(packet, offset + 2);
            int valueStart = offset + 4;
            int paddedLength = (attrLength + 3) & ~3;
            int nextOffset = valueStart + paddedLength;
            if (valueStart + attrLength > length) {
                return Optional.empty();
            }

            Optional<String> mapped = Optional.empty();
            if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                mapped = parseXorMappedAddress(packet, valueStart, attrLength);
            } else if (attrType == ATTR_MAPPED_ADDRESS) {
                mapped = parseMappedAddress(packet, valueStart, attrLength);
            }

            if (mapped.isPresent()) {
                return mapped;
            }

            offset = nextOffset;
        }

        return Optional.empty();
    }

    private static Optional<String> parseMappedAddress(byte[] packet, int offset, int length) {
        if (length < 8 || packet[offset + 1] != 0x01) {
            return Optional.empty();
        }

        int port = readU16(packet, offset + 2);
        byte[] ip = Arrays.copyOfRange(packet, offset + 4, offset + 8);
        try {
            InetAddress address = InetAddress.getByAddress(ip);
            if (!(address instanceof Inet4Address)) {
                return Optional.empty();
            }
            return Optional.of(address.getHostAddress() + ":" + port);
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> parseXorMappedAddress(byte[] packet, int offset, int length) {
        if (length < 8 || packet[offset + 1] != 0x01) {
            return Optional.empty();
        }

        int xPort = readU16(packet, offset + 2);
        int port = xPort ^ (STUN_MAGIC_COOKIE >>> 16);

        byte[] ip = Arrays.copyOfRange(packet, offset + 4, offset + 8);
        ip[0] = (byte) (ip[0] ^ 0x21);
        ip[1] = (byte) (ip[1] ^ 0x12);
        ip[2] = (byte) (ip[2] ^ (byte) 0xA4);
        ip[3] = (byte) (ip[3] ^ 0x42);
        try {
            InetAddress address = InetAddress.getByAddress(ip);
            if (!(address instanceof Inet4Address)) {
                return Optional.empty();
            }
            return Optional.of(address.getHostAddress() + ":" + port);
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static ServerSpec parseServerSpec(String stunServer) {
        if (stunServer == null || stunServer.isBlank()) {
            return null;
        }

        String[] parts = stunServer.trim().split(":", 2);
        String host = parts[0].trim();
        int port = 3478;
        if (host.isEmpty()) {
            return null;
        }
        if (parts.length == 2) {
            try {
                port = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        if (port < 1 || port > 65535) {
            return null;
        }
        return new ServerSpec(host, port);
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readMagicCookie(byte[] data) {
        return ((data[4] & 0xFF) << 24)
                | ((data[5] & 0xFF) << 16)
                | ((data[6] & 0xFF) << 8)
                | (data[7] & 0xFF);
    }

    private static void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeMagicCookie(byte[] data) {
        data[4] = (byte) 0x21;
        data[5] = (byte) 0x12;
        data[6] = (byte) 0xA4;
        data[7] = (byte) 0x42;
    }

    private record ServerSpec(String host, int port) {
    }
}

