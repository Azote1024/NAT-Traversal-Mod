package com.azote.nat_traversal_mod.net;

import com.azote.nat_traversal_mod.NatTraversalMod;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

final class SupabaseRoomsApiClient {
    private SupabaseRoomsApiClient() {
    }

    static Optional<String> fetchOpenRoomBody(String supabaseUrl, String supabaseKey, String roomName) {
        String encodedRoomName = URLEncoder.encode(roomName, StandardCharsets.UTF_8);
        String endpoint = supabaseUrl + "/rest/v1/rooms?select=host_ip,host_port,public_endpoint,relay_endpoint,relay_status,candidates,updated_at"
                + "&room_name=eq." + encodedRoomName
                + "&status=eq.open";

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(4))
                .header("apikey", supabaseKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = SupabaseRestClient.sendString(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                NatTraversalMod.LOGGER.warn(
                        "[nat-traversal-mod] Supabase request failed. status={}, room_name='{}'. Fallback to original target.",
                        response.statusCode(),
                        roomName
                );
                return Optional.empty();
            }
            return Optional.ofNullable(response.body());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] Supabase request exception. room_name='{}'. Fallback to original target.",
                    roomName,
                    exception
            );
            return Optional.empty();
        } catch (RuntimeException exception) {
            NatTraversalMod.LOGGER.warn(
                    "[nat-traversal-mod] Unexpected error while reading room. room_name='{}'. Fallback to original target.",
                    roomName,
                    exception
            );
            return Optional.empty();
        }
    }
}


