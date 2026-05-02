package com.azote.nat_traversal_mod.net;

import java.util.Optional;

interface QuicTransport {
    boolean isOperational();

    Optional<ResolvedTarget> tryActivate(RelayEndpoint endpoint, String roomName);
}


