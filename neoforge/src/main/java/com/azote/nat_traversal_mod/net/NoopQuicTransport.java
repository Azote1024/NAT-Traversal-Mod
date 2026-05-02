package com.azote.nat_traversal_mod.net;

import java.util.Optional;

final class NoopQuicTransport implements QuicTransport {
	@Override
	public boolean isOperational() {
		return false;
	}

	@Override
	public Optional<ResolvedTarget> tryActivate(RelayEndpoint endpoint, String roomName) {
		return Optional.empty();
	}
}


