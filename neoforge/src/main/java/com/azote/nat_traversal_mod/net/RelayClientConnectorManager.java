package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class RelayClientConnectorManager {
    private static volatile boolean started;
    private static volatile long relayUnreachableUntilMs;

    private RelayClientConnectorManager() {
    }

    public static synchronized boolean ensureStarted() {
        if (started) {
            return true;
        }

        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();

        if (!runtimeConfig.relayClientConnectorEnabled()) {
            return false;
        }

        String token = runtimeConfig.relayToken();
        if (token.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] relay_token is empty. Relay client connector disabled.");
            return false;
        }

        Optional<RelayEndpoint> endpoint = RelayEndpoint.parse(runtimeConfig.relayConnectEndpointClient(), "relay.connect_endpoint");
        if (endpoint.isEmpty()) {
            return false;
        }

        int localPort = runtimeConfig.relayClientLocalPort();
        Thread thread = new Thread(() -> runLoop(localPort), "nat-relay-client-connector");
        thread.setDaemon(true);
        thread.start();
        started = true;
        NatTraversalMod.LOGGER.info("[nat-traversal-mod] Relay client connector started on 127.0.0.1:{}", localPort);
        return true;
    }

    public static boolean isRelayUpstreamReachable(int timeoutMs) {
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        if (!runtimeConfig.relayClientConnectorEnabled()) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now < relayUnreachableUntilMs) {
            return false;
        }

        Optional<RelayEndpoint> endpoint = RelayEndpoint.parse(runtimeConfig.relayConnectEndpointClient(), "relay.connect_endpoint");
        if (endpoint.isEmpty()) {
            return false;
        }

        int normalizedTimeoutMs = Math.max(200, Math.min(timeoutMs, 1500));
        Socket probe = new Socket();
        try {
            probe.connect(new InetSocketAddress(endpoint.get().host(), endpoint.get().port()), normalizedTimeoutMs);
            return true;
        } catch (IOException exception) {
            // Short cooldown prevents tight retry loops when relay is clearly unreachable.
            relayUnreachableUntilMs = System.currentTimeMillis() + 3000L;
            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] Relay upstream is unreachable for fallback probe. endpoint='{}:{}', cooldown_ms=3000",
                    endpoint.get().host(),
                    endpoint.get().port()
            );
            return false;
        } finally {
            RelayIoBridge.closeQuietly(probe);
        }
    }

    private static void runLoop(int localPort) {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress("127.0.0.1", localPort));
            while (true) {
                Socket localSocket = serverSocket.accept();
                Thread thread = new Thread(() -> handleLocalConnection(localSocket), "nat-relay-client-session");
                thread.setDaemon(true);
                thread.start();
            }
        } catch (IOException exception) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Relay client connector stopped.", exception);
            started = false;
        }
    }

    private static void handleLocalConnection(Socket localSocket) {
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
        Optional<RelayEndpoint> endpoint = RelayEndpoint.parse(runtimeConfig.relayConnectEndpointClient(), "relay.connect_endpoint");
        String token = runtimeConfig.relayToken();
        if (endpoint.isEmpty() || token.isBlank()) {
            RelayIoBridge.closeQuietly(localSocket);
            return;
        }

        Socket relaySocket = new Socket();
        try {
            relaySocket.connect(new InetSocketAddress(endpoint.get().host(), endpoint.get().port()), 3000);
            sendHello(relaySocket, token, "client");
            RelayIoBridge.bridge(localSocket, relaySocket);
        } catch (IOException | InterruptedException exception) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Relay client session failed.", exception);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            RelayIoBridge.closeQuietly(localSocket);
            RelayIoBridge.closeQuietly(relaySocket);
        }
    }

    private static void sendHello(Socket socket, String token, String role) throws IOException {
        String hello = "HELLO " + token + " " + role + "\n";
        OutputStream out = socket.getOutputStream();
        out.write(hello.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}



