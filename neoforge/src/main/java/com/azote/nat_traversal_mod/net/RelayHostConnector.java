package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class RelayHostConnector {
    private volatile boolean running;
    private Thread worker;
    private int serverPort;

    public synchronized void start(int serverPort) {
        stop();
        this.serverPort = serverPort;
        this.running = true;
        this.worker = new Thread(this::runLoop, "nat-relay-host-connector");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void runLoop() {
        while (running) {
            try {
                RelaySessionConfig config = loadConfig();
                if (config == null) {
                    sleepQuietly(2000);
                    continue;
                }

                runSession(config);
                sleepQuietly(500);
            } catch (RuntimeException exception) {
                NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Relay host connector error.", exception);
                sleepQuietly(2000);
            }
        }
    }

    private void runSession(RelaySessionConfig config) {
        Socket relaySocket = new Socket();
        Socket localSocket = new Socket();
        try {
            relaySocket.connect(new InetSocketAddress(config.endpoint.host(), config.endpoint.port()), 3000);
            sendHello(relaySocket, config.token, "host");

            localSocket.connect(new InetSocketAddress("127.0.0.1", serverPort), 3000);

            NatTraversalMod.LOGGER.info(
                    "[nat-traversal-mod] Relay host connector paired. relay='{}:{}', local='127.0.0.1:{}'",
                    config.endpoint.host(),
                    config.endpoint.port(),
                    serverPort
            );

            RelayIoBridge.bridge(relaySocket, localSocket);
        } catch (IOException | InterruptedException exception) {
            if (running) {
                NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Relay host connector session failed.", exception);
            }
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            RelayIoBridge.closeQuietly(relaySocket);
            RelayIoBridge.closeQuietly(localSocket);
        }
    }

    private static void sendHello(Socket socket, String token, String role) throws IOException {
        String hello = "HELLO " + token + " " + role + "\n";
        OutputStream out = socket.getOutputStream();
        out.write(hello.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private RelaySessionConfig loadConfig() {
        RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();

        String status = runtimeConfig.relayStatus();
        if (!"ready".equalsIgnoreCase(status)) {
            return null;
        }

        String token = runtimeConfig.relayToken();
        if (token.isBlank()) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] relay_token is empty. Relay host connector disabled.");
            return null;
        }

        Optional<RelayEndpoint> endpoint = RelayEndpoint.parse(runtimeConfig.relayConnectEndpointServer(), "relay.connect_endpoint");
        return endpoint.map(value -> new RelaySessionConfig(value, token)).orElse(null);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private record RelaySessionConfig(RelayEndpoint endpoint, String token) {
    }
}



