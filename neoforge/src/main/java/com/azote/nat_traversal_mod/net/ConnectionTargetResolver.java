package com.azote.nat_traversal_mod.net;

import java.util.Optional;

public final class ConnectionTargetResolver {
	private ConnectionTargetResolver() {
	}

	public static Optional<ResolvedTarget> resolve() {

		return SupabaseRoomsClient.resolve();
	}
}

