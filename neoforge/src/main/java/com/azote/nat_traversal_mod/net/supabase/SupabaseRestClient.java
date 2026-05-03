package com.azote.nat_traversal_mod.net.supabase;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class SupabaseRestClient {
    enum ResponseCategory {
        SUCCESS,
        SCHEMA_MISMATCH,
        ENDPOINT_UNAVAILABLE,
        FAILURE
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private SupabaseRestClient() {
    }

    static HttpResponse<String> sendString(HttpRequest request) throws IOException, InterruptedException {
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<Void> sendDiscarding(HttpRequest request) throws IOException, InterruptedException {
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
    }

    static ResponseCategory classifyDefault(int statusCode) {
        return isSuccess(statusCode) ? ResponseCategory.SUCCESS : ResponseCategory.FAILURE;
    }

    static ResponseCategory classifySessionQuery(int statusCode) {
        if (isSuccess(statusCode)) {
            return ResponseCategory.SUCCESS;
        }
        if (statusCode == 400) {
            return ResponseCategory.SCHEMA_MISMATCH;
        }
        if (statusCode == 404) {
            return ResponseCategory.ENDPOINT_UNAVAILABLE;
        }
        return ResponseCategory.FAILURE;
    }

    static ResponseCategory classifyPeerAttempts(int statusCode) {
        if (isSuccess(statusCode)) {
            return ResponseCategory.SUCCESS;
        }
        if (statusCode == 400 || statusCode == 404) {
            return ResponseCategory.ENDPOINT_UNAVAILABLE;
        }
        return ResponseCategory.FAILURE;
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    static HttpRequest buildAuthorizedGetJsonRequest(String endpoint, String apiKey, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("apikey", apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    static HttpRequest buildAuthorizedJsonRequest(
            String endpoint,
            String method,
            String apiKey,
            String body,
            Duration timeout,
            String prefer
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("apikey", apiKey)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));

        if (prefer != null && !prefer.isBlank()) {
            builder.header("Prefer", prefer);
        }

        return builder.build();
    }
}

