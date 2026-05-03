package com.azote.nat_traversal_mod.net.supabase;

import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigLoader;
import com.azote.nat_traversal_mod.config.runtime.RuntimeConfigSnapshot;
import com.azote.nat_traversal_mod.NatTraversalMod;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupabaseQuicSessionClient {
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
	private static final String QUIC_SESSION_SELECT_RICH =
			"room_name,quic_endpoint,host_public_endpoint,quic_status,punch_endpoint,punch_status,punch_token,host_probe_sent_at,attempt_id,last_error_code,host_nat_type,route_decision,relay_reason,status,updated_at";
	private static final String QUIC_SESSION_SELECT_FALLBACK =
			"room_name,quic_endpoint,quic_status,punch_endpoint,punch_status,punch_token,host_nat_type,route_decision,relay_reason,status,updated_at";
	private static volatile boolean peerAttemptsUnavailableLogged;
	private static final Pattern PUNCH_WINDOW_MS_PATTERN = Pattern.compile("\"punch_window_ms\"\\s*:\\s*(\\d+)");

	private SupabaseQuicSessionClient() {
	}

	public static Optional<String> fetchSessionBody(String roomName) {
		Optional<SupabaseAuth> auth = loadAuth();
		if (auth.isEmpty() || roomName.isBlank()) {
			return Optional.empty();
		}

		String encodedRoomName = URLEncoder.encode(roomName, StandardCharsets.UTF_8);
		SupabaseAuth loadedAuth = auth.get();

		try {
			Optional<String> richBody = fetchSessionBodyOnce(
					loadedAuth.supabaseUrl(),
					loadedAuth.supabaseKey(),
					encodedRoomName,
					QUIC_SESSION_SELECT_RICH,
					roomName,
					false
			);
			if (richBody.isPresent()) {
				return richBody;
			}

			return fetchSessionBodyOnce(
					loadedAuth.supabaseUrl(),
					loadedAuth.supabaseKey(),
					encodedRoomName,
					QUIC_SESSION_SELECT_FALLBACK,
					roomName,
					true
			);
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] QUIC session query exception. room_name='{}'.",
					roomName,
					exception
			);
			return Optional.empty();
		}
	}

	private static Optional<String> fetchSessionBodyOnce(
			String supabaseUrl,
			String supabaseKey,
			String encodedRoomName,
			String selectColumns,
			String roomName,
			boolean fallbackMode
	) throws IOException, InterruptedException {
		String endpoint = supabaseUrl
				+ SupabaseApiPaths.QUIC_SESSIONS + "?select=" + selectColumns
				+ "&room_name=eq." + encodedRoomName
				+ "&status=eq.open";

		HttpRequest request = SupabaseRestClient.buildAuthorizedGetJsonRequest(endpoint, supabaseKey, REQUEST_TIMEOUT);

		HttpResponse<String> response = SupabaseRestClient.sendString(request);
		int statusCode = response.statusCode();
		SupabaseRestClient.ResponseCategory category = SupabaseRestClient.classifySessionQuery(statusCode);
		if (category == SupabaseRestClient.ResponseCategory.SUCCESS) {
			return Optional.of(response.body());
		}

		if (!fallbackMode && category == SupabaseRestClient.ResponseCategory.SCHEMA_MISMATCH) {
			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] QUIC session query schema mismatch. retry with legacy select. room_name='{}'",
					roomName
			);
			return Optional.empty();
		}

		if (category == SupabaseRestClient.ResponseCategory.ENDPOINT_UNAVAILABLE) {
			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] QUIC session endpoint unavailable. status={}, room_name='{}'.",
					statusCode,
					roomName
			);
			return Optional.empty();
		}

		NatTraversalMod.LOGGER.info(
				"[nat-traversal-mod] QUIC session query failed. status={}, room_name='{}'.",
				statusCode,
				roomName
		);
		return Optional.empty();
	}

	public static Optional<String> fetchCurrentAttemptId(String roomName) {
		return fetchSessionBody(roomName).flatMap(SupabaseQuicSessionClient::extractAttemptId);
	}

	public static Optional<PeerPunchSyncInfo> fetchLatestPeerPunchSyncInfo(String roomName) {
		Optional<SupabaseAuth> auth = loadAuth();
		if (auth.isEmpty() || roomName.isBlank()) {
			return Optional.empty();
		}

		SupabaseAuth loadedAuth = auth.get();
		String encodedRoomName = URLEncoder.encode(roomName, StandardCharsets.UTF_8);
		String endpoint = loadedAuth.supabaseUrl()
				+ SupabaseApiPaths.QUIC_PEER_ATTEMPTS
				+ "?select=client_key,client_public_endpoint,attempt_id,punch_sync_token,punch_window_opened_at,punch_window_ms,last_transition,updated_at"
				+ "&room_name=eq." + encodedRoomName
				+ "&order=updated_at.desc"
				+ "&limit=1";

		HttpRequest request = SupabaseRestClient.buildAuthorizedGetJsonRequest(endpoint, loadedAuth.supabaseKey(), REQUEST_TIMEOUT);
		try {
			HttpResponse<String> response = SupabaseRestClient.sendString(request);
			int status = response.statusCode();
			SupabaseRestClient.ResponseCategory category = SupabaseRestClient.classifyPeerAttempts(status);
			if (category != SupabaseRestClient.ResponseCategory.SUCCESS) {
				if (category == SupabaseRestClient.ResponseCategory.ENDPOINT_UNAVAILABLE && !peerAttemptsUnavailableLogged) {
					peerAttemptsUnavailableLogged = true;
					NatTraversalMod.LOGGER.info(
							"[nat-traversal-mod] quic_peer_attempts endpoint unavailable for punch sync query. status={}",
							status
					);
				}
				return Optional.empty();
			}

			String body = response.body();
			Optional<String> clientEndpoint = JsonRegexDtoParser.findStringField(body, "client_public_endpoint");
			if (clientEndpoint.isEmpty() || clientEndpoint.get().isBlank()) {
				return Optional.empty();
			}

			String attemptId = JsonRegexDtoParser.findStringField(body, "attempt_id").orElse("");
			String clientKey = JsonRegexDtoParser.findStringField(body, "client_key").orElse("");
			String syncToken = JsonRegexDtoParser.findStringField(body, "punch_sync_token").orElse("");
			String windowOpenedAt = JsonRegexDtoParser.findStringField(body, "punch_window_opened_at").orElse("");
			String lastTransition = JsonRegexDtoParser.findStringField(body, "last_transition").orElse("");
			String updatedAt = JsonRegexDtoParser.findStringField(body, "updated_at").orElse("");
			int windowMs = parseWindowMs(body);
			return Optional.of(new PeerPunchSyncInfo(clientKey, clientEndpoint.get(), attemptId, syncToken, windowOpenedAt, windowMs, lastTransition, updatedAt));
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] quic_peer_attempts punch sync query exception. room_name='{}'",
					roomName,
					exception
			);
			return Optional.empty();
		}
	}

	public static Optional<PeerPunchSyncInfo> fetchPeerPunchSyncInfo(String roomName, String clientKey, String attemptId) {
		Optional<SupabaseAuth> auth = loadAuth();
		if (auth.isEmpty() || roomName.isBlank() || clientKey.isBlank() || attemptId.isBlank()) {
			return Optional.empty();
		}

		SupabaseAuth loadedAuth = auth.get();
		String endpoint = loadedAuth.supabaseUrl()
				+ SupabaseApiPaths.QUIC_PEER_ATTEMPTS
				+ "?select=client_key,client_public_endpoint,attempt_id,punch_sync_token,punch_window_opened_at,punch_window_ms,last_transition,updated_at"
				+ "&room_name=eq." + URLEncoder.encode(roomName, StandardCharsets.UTF_8)
				+ "&client_key=eq." + URLEncoder.encode(clientKey, StandardCharsets.UTF_8)
				+ "&attempt_id=eq." + URLEncoder.encode(attemptId, StandardCharsets.UTF_8)
				+ "&limit=1";

		HttpRequest request = SupabaseRestClient.buildAuthorizedGetJsonRequest(endpoint, loadedAuth.supabaseKey(), REQUEST_TIMEOUT);
		try {
			HttpResponse<String> response = SupabaseRestClient.sendString(request);
			int status = response.statusCode();
			SupabaseRestClient.ResponseCategory category = SupabaseRestClient.classifyPeerAttempts(status);
			if (category != SupabaseRestClient.ResponseCategory.SUCCESS) {
				return Optional.empty();
			}

			String body = response.body();
			Optional<String> clientEndpoint = JsonRegexDtoParser.findStringField(body, "client_public_endpoint");
			if (clientEndpoint.isEmpty()) {
				return Optional.empty();
			}

			String syncToken = JsonRegexDtoParser.findStringField(body, "punch_sync_token").orElse("");
			String windowOpenedAt = JsonRegexDtoParser.findStringField(body, "punch_window_opened_at").orElse("");
			String lastTransition = JsonRegexDtoParser.findStringField(body, "last_transition").orElse("");
			String updatedAt = JsonRegexDtoParser.findStringField(body, "updated_at").orElse("");
			int windowMs = parseWindowMs(body);
			return Optional.of(new PeerPunchSyncInfo(clientKey, clientEndpoint.get(), attemptId, syncToken, windowOpenedAt, windowMs, lastTransition, updatedAt));
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return Optional.empty();
		}
	}

	public static void markClientAttemptStarted(String roomName, String attemptId) {
		patchSession(
				roomName,
				new SupabaseJsonObjectBuilder()
						.addString("attempt_id", attemptId)
						.addString("last_error_code", "")
						.build(),
				"Failed to mark client attempt id"
		);
	}

	public static void markClientPunchSent(String roomName, String attemptId) {
		patchSession(
				roomName,
				new SupabaseJsonObjectBuilder()
						.addString("attempt_id", attemptId)
						.addString("punch_status", "client_probe_sent")
						.addString("client_punch_sent_at", Instant.now().toString())
						.addString("last_error_code", "")
						.build(),
				"Failed to mark client punch status"
		);
	}

	public static void markHostPunchProbing(String roomName) {
		patchSession(
				roomName,
				new SupabaseJsonObjectBuilder()
						.addString("punch_status", "probing")
						.addString("host_probe_sent_at", Instant.now().toString())
						.addString("last_error_code", "")
						.build(),
				"Failed to mark host punch probing"
		);
	}

	public static void updateHostPublicEndpoint(String roomName, String hostPublicEndpoint) {
		String normalizedEndpoint = hostPublicEndpoint == null ? "" : hostPublicEndpoint.trim();
		if (roomName.isBlank() || normalizedEndpoint.isBlank()) {
			return;
		}
		patchSession(
				roomName,
				new SupabaseJsonObjectBuilder()
						.addString("host_public_endpoint", normalizedEndpoint)
						.addString("punch_endpoint", normalizedEndpoint)
						.addString("updated_at", Instant.now().toString())
						.build(),
				"Failed to update host public endpoint"
		);
	}

	public static void markHostPunchEstablished(String roomName) {
		patchSession(
				roomName,
				new SupabaseJsonObjectBuilder()
						.addString("punch_status", "established")
						.addString("last_error_code", "")
						.build(),
				"Failed to mark host punch established"
		);
	}

	public static void markHostPunchDown(String roomName) {
		markHostPunchDown(roomName, "");
	}

	public static void markHostPunchDown(String roomName, String errorCode) {
		String normalizedErrorCode = errorCode == null ? "" : errorCode.trim();
		if (normalizedErrorCode.length() > 64) {
			normalizedErrorCode = normalizedErrorCode.substring(0, 64);
		}
		patchSession(
				roomName,
				new SupabaseJsonObjectBuilder()
						.addString("punch_status", "down")
						.addString("last_error_code", normalizedErrorCode)
						.build(),
				"Failed to mark host punch down"
		);
	}

	public static void markRouteDecisionQuicTry(String roomName) {
		markRouteDecision(roomName, "quic_try");
	}

	public static void markRouteDecisionRelayForced(String roomName) {
		markRouteDecision(roomName, "relay_forced");
	}

	private static void markRouteDecision(String roomName, String decision) {
		patchSession(
				roomName,
				new SupabaseJsonObjectBuilder()
						.addString("route_decision", decision)
						.addString("relay_reason", "")
						.addString("route_decided_at", Instant.now().toString())
						.build(),
				"Failed to mark route decision"
		);
	}

	public static void upsertPeerAttempt(
			String roomName,
			String clientKey,
			String attemptId,
			String clientNatType,
			String decision,
			String punchStatus,
			String lastErrorCode,
			boolean closed
	) {
		Optional<SupabaseAuth> auth = loadAuth();
		if (auth.isEmpty() || roomName.isBlank() || clientKey.isBlank() || attemptId.isBlank()) {
			return;
		}
		SupabaseAuth loadedAuth = auth.get();

		String endpoint = loadedAuth.supabaseUrl() + SupabaseApiPaths.QUIC_PEER_ATTEMPTS;
		String now = Instant.now().toString();
		SupabaseJsonObjectBuilder bodyBuilder = new SupabaseJsonObjectBuilder()
				.addString("room_name", roomName)
				.addString("client_key", clientKey)
				.addString("attempt_id", attemptId)
				.addString("client_nat_type", normalizeNatType(clientNatType))
				.addString("decision", decision == null ? "" : decision)
				.addString("punch_status", punchStatus == null ? "" : punchStatus)
				.addString("last_error_code", lastErrorCode == null ? "" : lastErrorCode)
				.addString("updated_at", now);
		if (closed) {
			bodyBuilder.addString("closed_at", now);
		}
		String body = bodyBuilder.build();

		HttpRequest request = SupabaseRestClient.buildAuthorizedJsonRequest(
				endpoint,
				"POST",
				loadedAuth.supabaseKey(),
				body,
				REQUEST_TIMEOUT,
				"resolution=merge-duplicates,return=minimal"
		);

		try {
			HttpResponse<Void> response = SupabaseRestClient.sendDiscarding(request);
			int status = response.statusCode();
			SupabaseRestClient.ResponseCategory category = SupabaseRestClient.classifyPeerAttempts(status);
			if (category == SupabaseRestClient.ResponseCategory.SUCCESS) {
				return;
			}

			if (category == SupabaseRestClient.ResponseCategory.ENDPOINT_UNAVAILABLE && !peerAttemptsUnavailableLogged) {
				peerAttemptsUnavailableLogged = true;
				NatTraversalMod.LOGGER.info(
						"[nat-traversal-mod] quic_peer_attempts endpoint unavailable. status={}",
						status
				);
			}
			if (category == SupabaseRestClient.ResponseCategory.ENDPOINT_UNAVAILABLE) {
				return;
			}

			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] quic_peer_attempts upsert failed. status={}, room_name='{}', attempt_id='{}'",
					status,
					roomName,
					attemptId
			);
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] quic_peer_attempts upsert exception. room_name='{}', attempt_id='{}'",
					roomName,
					attemptId,
					exception
			);
		}
	}

	public static void upsertPeerAttemptSync(
			String roomName,
			String clientKey,
			String attemptId,
			String clientPublicEndpoint,
			String hostPublicEndpoint,
			String punchSyncToken,
			String punchWindowOpenedAt,
			int punchWindowMs,
			String lastTransition
	) {
		Optional<SupabaseAuth> auth = loadAuth();
		if (auth.isEmpty() || roomName.isBlank() || clientKey.isBlank() || attemptId.isBlank()) {
			return;
		}

		SupabaseAuth loadedAuth = auth.get();
		String endpoint = loadedAuth.supabaseUrl() + SupabaseApiPaths.QUIC_PEER_ATTEMPTS;
		String now = Instant.now().toString();
		SupabaseJsonObjectBuilder bodyBuilder = new SupabaseJsonObjectBuilder()
				.addString("room_name", roomName)
				.addString("client_key", clientKey)
				.addString("attempt_id", attemptId)
				.addString("client_public_endpoint", clientPublicEndpoint == null ? "" : clientPublicEndpoint)
				.addString("host_public_endpoint", hostPublicEndpoint == null ? "" : hostPublicEndpoint)
				.addString("punch_sync_token", punchSyncToken == null ? "" : punchSyncToken)
				.addString("last_transition", lastTransition == null ? "" : lastTransition)
				.addString("updated_at", now)
				.addNumber("punch_window_ms", Math.max(0, punchWindowMs));

		if (punchWindowOpenedAt != null && !punchWindowOpenedAt.isBlank()) {
			bodyBuilder.addString("punch_window_opened_at", punchWindowOpenedAt);
		}

		String body = bodyBuilder.build();
		HttpRequest request = SupabaseRestClient.buildAuthorizedJsonRequest(
				endpoint,
				"POST",
				loadedAuth.supabaseKey(),
				body,
				REQUEST_TIMEOUT,
				"resolution=merge-duplicates,return=minimal"
		);

		try {
			HttpResponse<Void> response = SupabaseRestClient.sendDiscarding(request);
			int status = response.statusCode();
			SupabaseRestClient.ResponseCategory category = SupabaseRestClient.classifyPeerAttempts(status);
			if (category == SupabaseRestClient.ResponseCategory.SUCCESS) {
				return;
			}

			if (category == SupabaseRestClient.ResponseCategory.ENDPOINT_UNAVAILABLE && !peerAttemptsUnavailableLogged) {
				peerAttemptsUnavailableLogged = true;
				NatTraversalMod.LOGGER.info(
						"[nat-traversal-mod] quic_peer_attempts sync fields unavailable. status={}",
						status
				);
			}
			if (category == SupabaseRestClient.ResponseCategory.ENDPOINT_UNAVAILABLE) {
				return;
			}

			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] quic_peer_attempts sync upsert failed. status={}, room_name='{}', attempt_id='{}'",
					status,
					roomName,
					attemptId
			);
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] quic_peer_attempts sync upsert exception. room_name='{}', attempt_id='{}'",
					roomName,
					attemptId,
					exception
			);
		}
	}

	private static String normalizeNatType(String natType) {
		String normalized = natType == null ? "" : natType.trim().toLowerCase();
		if (normalized.equals("open") || normalized.equals("port_restricted") || normalized.equals("symmetric")) {
			return normalized;
		}
		return "unknown";
	}

	private static Optional<String> extractAttemptId(String responseBody) {
		return JsonRegexDtoParser.findStringField(responseBody, "attempt_id")
				.filter(attemptId -> !attemptId.isEmpty());
	}

	private static int parseWindowMs(String body) {
		Matcher matcher = PUNCH_WINDOW_MS_PATTERN.matcher(body);
		if (!matcher.find()) {
			return 0;
		}
		return parsePositiveInt(matcher.group(1)).orElse(0);
	}

	private static Optional<Integer> parsePositiveInt(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		try {
			int parsed = Integer.parseInt(value.trim());
			return Optional.of(Math.max(0, parsed));
		} catch (NumberFormatException exception) {
			return Optional.empty();
		}
	}


	private static void patchSession(String roomName, String payload, String errorLogMessage) {
		Optional<SupabaseAuth> auth = loadAuth();
		if (auth.isEmpty() || roomName.isBlank()) {
			return;
		}
		SupabaseAuth loadedAuth = auth.get();

		String encodedRoomName = URLEncoder.encode(roomName, StandardCharsets.UTF_8);
		String endpoint = loadedAuth.supabaseUrl() + SupabaseApiPaths.QUIC_SESSIONS + "?room_name=eq." + encodedRoomName;

		HttpRequest request = SupabaseRestClient.buildAuthorizedJsonRequest(
				endpoint,
				"PATCH",
				loadedAuth.supabaseKey(),
				payload,
				REQUEST_TIMEOUT,
				"return=minimal"
		);

		try {
			HttpResponse<Void> response = SupabaseRestClient.sendDiscarding(request);
			int status = response.statusCode();
			if (SupabaseRestClient.classifyDefault(status) != SupabaseRestClient.ResponseCategory.SUCCESS) {
				NatTraversalMod.LOGGER.info(
						"[nat-traversal-mod] {}. status={}, room_name='{}'",
						errorLogMessage,
						status,
						roomName
				);
			}
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			NatTraversalMod.LOGGER.info("[nat-traversal-mod] {}. room_name='{}'", errorLogMessage, roomName, exception);
		}
	}

	private static Optional<SupabaseAuth> loadAuth() {
		RuntimeConfigSnapshot runtimeConfig = RuntimeConfigLoader.load();
		String supabaseUrl = runtimeConfig.supabaseUrl();
		String supabaseKey = runtimeConfig.supabaseApiKey();
		if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new SupabaseAuth(supabaseUrl, supabaseKey));
	}

	private record SupabaseAuth(String supabaseUrl, String supabaseKey) {
	}

	public record PeerPunchSyncInfo(
			String clientKey,
			String clientPublicEndpoint,
			String attemptId,
			String punchSyncToken,
			String punchWindowOpenedAt,
			int punchWindowMs,
			String lastTransition,
			String updatedAt
	) {
	}
}


