package com.azote.nat_traversal_mod.config.runtime;

public enum ConnectStrategy {
    TCP_ONLY,
    QUIC_FIRST,
    RELAY_FIRST,
    TCP_QUIC_RELAY
}

