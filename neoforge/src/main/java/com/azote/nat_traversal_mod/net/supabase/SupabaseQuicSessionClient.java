package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.NatTraversalMod;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SupabaseQuicSessionClient {
	private static final Pattern ATTEMPT_ID_PATTERN = Pattern.compile("\"attempt_id\"\\s*:\\s*\"([^\"]*)\"");
	private static volatile boolean peerAttemptsUnavailableLogged;

	private SupabaseQuicSessionClient() {
	}

	static Optional<String> fetchSessionBody(String roomName) {
		String supabaseUrl = Config.supabaseUrl();
		String supabaseKey = Config.supabaseKey();
		if (supabaseUrl.isBlank() || supabaseKey.isBlank() || roomName.isBlank()) {
			return Optional.empty();
		}

		String encodedRoomName = URLEncoder.encode(roomName, StandardCharsets.UTF_8);

		try {
			Optional<String> richBody = fetchSessionBodyOnce(
					supabaseUrl,
					supabaseKey,
					encodedRoomName,
					"room_name,quic_endpoint,quic_status,punch_endpoint,punch_status,punch_token,host_probe_sent_at,attempt_id,last_error_code,host_nat_type,route_decision,relay_reason,status,updated_at",
					roomName,
					false
			);
			if (richBody.isPresent()) {
				return richBody;
			}

			return fetchSessionBodyOnce(
					supabaseUrl,
					supabaseKey,
					encodedRoomName,
					"room_name,quic_endpoint,quic_status,punch_endpoint,punch_status,punch_token,host_nat_type,route_decision,relay_reason,status,updated_at",
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
				+ "/rest/v1/quic_sessions?select=" + selectColumns
				+ "&room_name=eq." + encodedRoomName
				+ "&status=eq.open";

		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(Duration.ofSeconds(4))
				.header("apikey", supabaseKey)
				.header("Accept", "application/json")
				.GET()
				.build();

		HttpResponse<String> response = SupabaseRestClient.sendString(request);
		if (response.statusCode() >= 200 && response.statusCode() < 300) {
			return Optional.of(response.body());
		}

		if (!fallbackMode && response.statusCode() == 400) {
			NatTraversalMod.LOGGER.info(
					"[nat-traversal-mod] QUIC session query schema mismatch. retry with legacy select. room_name='{}'",
					roomName
			);
			return Optional.empty();
		}

		NatTraversalMod.LOGGER.info(
				"[nat-traversal-mod] QUIC session query failed. status={}, room_name='{}'.",
				response.statusCode(),
				roomName
		);
		return Optional.empty();
	}

	static Optional<String> fetchCurrentAttemptId(String roomName) {
		return fetchSessionBody(roomName).flatMap(SupabaseQuicSessionClient::extractAttemptId);
	}

	static void markClientAttemptStarted(String roomName, String attemptId) {
		patchSession(roomName, "{"
				+ "\"attempt_id\":\"" + SupabaseJsonUtil.escape(attemptId) + "\","
				+ "\"last_error_code\":\"\""
				+ "}", "Failed to mark client attempt id");
	}

	static void markClientPunchSent(String roomName, String attemptId) {
		patchSession(roomName, "{"
				+ "\"attempt_id\":\"" + SupabaseJsonUtil.escape(attemptId) + "\","
				+ "\"punch_status\":\"client_probe_sent\","
				+ "\"client_punch_sent_at\":\"" + Instant.now() + "\","
				+ "\"last_error_code\":\"\""
				+ "}", "Failed to mark client punch status");
	}

	static void markHostPunchProbing(String roomName) {
		patchSession(roomName, "{"
				+ "\"punch_status\":\"probing\","
				+ "\"host_probe_sent_at\":\"" + Instant.now() + "\","
				+ "\"last_error_code\":\"\""
				+ "}", "Failed to mark host punch probing");
	}

	static void markHostPunchEstablished(String roomName) {
		patchSession(roomName, "{"
				+ "\"punch_status\":\"established\","
				+ "\"last_error_code\":\"\""
				+ "}", "Failed to mark host punch established");
	}

	static void markHostPunchDown(String roomName) {
		markHostPunchDown(roomName, "");
	}

	static void markHostPunchDown(String roomName, String errorCode) {
		String normalizedErrorCode = errorCode == null ? "" : errorCode.trim();
		if (normalizedErrorCode.length() > 64) {
			normalizedErrorCode = normalizedErrorCode.substring(0, 64);
		}
		patchSession(roomName, "{"
				+ "\"punch_status\":\"down\","
				+ "\"last_error_code\":\"" + SupabaseJsonUtil.escape(normalizedErrorCode) + "\""
				+ "}", "Failed to mark host punch down");
	}

	static void markRouteDecisionQuicTry(String roomName) {
		patchSession(roomName, "{"
				+ "\"route_decision\":\"quic_try\","
				+ "\"relay_reason\":\"\","
				+ "\"route_decided_at\":\"" + Instant.now() + "\""
				+ "}", "Failed to mark route decision");
	}

	static void upsertPeerAttempt(
			String roomName,
			String clientKey,
			String attemptId,
			String clientNatType,
			String decision,
			String punchStatus,
			String lastErrorCode,
			boolean closed
	) {
		String supabaseUrl = Config.supabaseUrl();
		String supabaseKey = Config.supabaseKey();
		if (supabaseUrl.isBlank() || supabaseKey.isBlank() || roomName.isBlank() || clientKey.isBlank() || attemptId.isBlank()) {
			return;
		}

		String endpoint = supabaseUrl + "/rest/v1/quic_peer_attempts";
		String now = Instant.now().toString();
		String body = "{"
				+ "\"room_name\":\"" + SupabaseJsonUtil.escape(roomName) + "\","
				+ "\"client_key\":\"" + SupabaseJsonUtil.escape(clientKey) + "\","
				+ "\"attempt_id\":\"" + SupabaseJsonUtil.escape(attemptId) + "\","
				+ "\"client_nat_type\":\"" + SupabaseJsonUtil.escape(normalizeNatType(clientNatType)) + "\","
				+ "\"decision\":\"" + SupabaseJsonUtil.escape(decision == null ? "" : decision) + "\","
				+ "\"punch_status\":\"" + SupabaseJsonUtil.escape(punchStatus == null ? "" : punchStatus) + "\","
				+ "\"last_error_code\":\"" + SupabaseJsonUtil.escape(lastErrorCode == null ? "" : lastErrorCode) + "\","
				+ "\"updated_at\":\"" + now + "\""
				+ (closed ? ",\"closed_at\":\"" + now + "\"" : "")
				+ "}";

		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(Duration.ofSeconds(4))
				.header("apikey", supabaseKey)
				.header("Content-Type", "application/json")
				.header("Prefer", "resolution=merge-duplicates,return=minimal")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<Void> response = SupabaseRestClient.sendDiscarding(request);
			int status = response.statusCode();
			if (status >= 200 && status < 300) {
				return;
			}

			if ((status == 400 || status == 404) && !peerAttemptsUnavailableLogged) {
				peerAttemptsUnavailableLogged = true;
				NatTraversalMod.LOGGER.info(
						"[nat-traversal-mod] quic_peer_attempts endpoint unavailable. status={}",
						status
				);
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

	private static String normalizeNatType(String natType) {
		String normalized = natType == null ? "" : natType.trim().toLowerCase();
		if (normalized.equals("open") || normalized.equals("port_restricted") || normalized.equals("symmetric")) {
			return normalized;
		}
		return "unknown";
	}

	private static Optional<String> extractAttemptId(String responseBody) {
		Matcher matcher = ATTEMPT_ID_PATTERN.matcher(responseBody);
		if (!matcher.find()) {
			return Optional.empty();
		}

		String attemptId = matcher.group(1).trim();
		if (attemptId.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(attemptId);
	}


	private static void patchSession(String roomName, String payload, String errorLogMessage) {
		String supabaseUrl = Config.supabaseUrl();
		String supabaseKey = Config.supabaseKey();
		if (supabaseUrl.isBlank() || supabaseKey.isBlank() || roomName.isBlank()) {
			return;
		}

		String encodedRoomName = URLEncoder.encode(roomName, StandardCharsets.UTF_8);
		String endpoint = supabaseUrl + "/rest/v1/quic_sessions?room_name=eq." + encodedRoomName;

		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(Duration.ofSeconds(4))
				.header("apikey", supabaseKey)
				.header("Content-Type", "application/json")
				.header("Prefer", "return=minimal")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();

		try {
			SupabaseRestClient.sendDiscarding(request);
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			NatTraversalMod.LOGGER.info("[nat-traversal-mod] {}. room_name='{}'", errorLogMessage, roomName, exception);
		}
	}
}


