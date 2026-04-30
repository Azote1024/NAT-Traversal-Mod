package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;

import java.util.Optional;

public final class ConnectionTargetResolver {
	private ConnectionTargetResolver() {
	}

	public static Optional<ResolvedTarget> resolve() {
		if (Config.stunEnabled()) {
			Nat_traversal_mod.LOGGER.info(
					"[nat-traversal-mod] stun_enabled=true. STUN-assisted publish is enabled. Resolver uses room public_endpoint when available. stun_server='{}', stun_timeout_ms={}",
					Config.stunServer(),
					Config.stunTimeoutMs()
			);
		}

		return SupabaseRoomsClient.resolve();
	}
}

