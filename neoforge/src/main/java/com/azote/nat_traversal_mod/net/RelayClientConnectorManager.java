package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.NatTraversalMod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class RelayClientConnectorManager {
    private static volatile boolean started;

    private RelayClientConnectorManager() {
    }

    public static synchronized boolean ensureStarted() {
        if (started) {
            return true;
        }

        if (!Config.relayClientConnectorEnabled()) {
            return false;
        }

        String token = Config.relayToken();
        if (token.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] relay_token is empty. Relay client connector disabled.");
            return false;
        }

        Optional<RelayEndpoint> endpoint = RelayEndpoint.parse(Config.relayConnectEndpointForClient(), "relay_connect_endpoint_client");
        if (endpoint.isEmpty()) {
            return false;
        }

        int localPort = Config.relayClientLocalPort();
        Thread thread = new Thread(() -> runLoop(localPort), "nat-relay-client-connector");
        thread.setDaemon(true);
        thread.start();
        started = true;
        NatTraversalMod.LOGGER.info("[nat-traversal-mod] Relay client connector started on 127.0.0.1:{}", localPort);
        return true;
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
        Optional<RelayEndpoint> endpoint = RelayEndpoint.parse(Config.relayConnectEndpointForClient(), "relay_connect_endpoint_client");
        String token = Config.relayToken();
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



