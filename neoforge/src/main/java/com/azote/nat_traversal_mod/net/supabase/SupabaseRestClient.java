package com.azote.nat_traversal_mod.net.supabase;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class SupabaseRestClient {
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
}

