package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;

import java.util.Optional;

public record RelayEndpoint(String host, int port) {
    public static Optional<RelayEndpoint> parse(String endpoint, String contextName) {
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }

        String[] parts = endpoint.trim().split(":", 2);
        if (parts.length != 2) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Invalid relay endpoint format in {}: '{}'", contextName, endpoint);
            return Optional.empty();
        }

        String host = parts[0].trim();
        int port;
        try {
            port = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ignored) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Invalid relay endpoint port in {}: '{}'", contextName, endpoint);
            return Optional.empty();
        }

        if (host.isBlank() || port < 1 || port > 65535) {
            NatTraversalMod.LOGGER.warn("[nat-traversal-mod] Invalid relay endpoint value in {}: '{}'", contextName, endpoint);
            return Optional.empty();
        }

        return Optional.of(new RelayEndpoint(host, port));
    }
}


