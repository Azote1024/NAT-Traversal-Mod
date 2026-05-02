package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.Config;
import com.azote.nat_traversal_mod.Nat_traversal_mod;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

final class SupabaseQuicSessionClient {
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.build();

	private SupabaseQuicSessionClient() {
	}

	static Optional<String> fetchSessionBody(String roomName) {
		String supabaseUrl = Config.supabaseUrl();
		String supabaseKey = Config.supabaseKey();
		if (supabaseUrl.isBlank() || supabaseKey.isBlank() || roomName.isBlank()) {
			return Optional.empty();
		}

		String encodedRoomName = URLEncoder.encode(roomName, StandardCharsets.UTF_8);
		String endpoint = supabaseUrl
				+ "/rest/v1/quic_sessions?select=room_name,quic_endpoint,quic_status,punch_endpoint,punch_status,punch_token,status,updated_at"
				+ "&room_name=eq." + encodedRoomName
				+ "&status=eq.open";

		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(Duration.ofSeconds(4))
				.header("apikey", supabaseKey)
				.header("Accept", "application/json")
				.GET()
				.build();

		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				Nat_traversal_mod.LOGGER.info(
						"[nat-traversal-mod] QUIC session query failed. status={}, room_name='{}'.",
						response.statusCode(),
						roomName
				);
				return Optional.empty();
			}
			return Optional.of(response.body());
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			Nat_traversal_mod.LOGGER.info(
					"[nat-traversal-mod] QUIC session query exception. room_name='{}'.",
					roomName,
					exception
			);
			return Optional.empty();
		}
	}

	static void markClientPunchSent(String roomName) {
		patchSession(roomName, "{"
				+ "\"punch_status\":\"client_probe_sent\","
				+ "\"client_punch_sent_at\":\"" + Instant.now() + "\""
				+ "}", "Failed to mark client punch status");
	}

	static void markHostPunchProbing(String roomName) {
		patchSession(roomName, "{"
				+ "\"punch_status\":\"probing\""
				+ "}", "Failed to mark host punch probing");
	}

	static void markHostPunchEstablished(String roomName) {
		patchSession(roomName, "{"
				+ "\"punch_status\":\"established\""
				+ "}", "Failed to mark host punch established");
	}

	static void markHostPunchDown(String roomName) {
		patchSession(roomName, "{"
				+ "\"punch_status\":\"down\""
				+ "}", "Failed to mark host punch down");
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
			HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			Nat_traversal_mod.LOGGER.info("[nat-traversal-mod] {}. room_name='{}'", errorLogMessage, roomName, exception);
		}
	}
}

