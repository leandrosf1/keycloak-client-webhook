package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** One HTTP attempt per persisted claim; retry scheduling is the Outbox worker's job. */
public final class ClientWebhookOutboxHttpSender {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public int send(URI url, int timeoutMs, ClientWebhookPayload payload, String jwt)
            throws IOException, InterruptedException {
        if (jwt == null || jwt.isBlank()) {
            throw new IllegalArgumentException("Missing signed JWT");
        }
        if (timeoutMs < 250 || timeoutMs > 60000) {
            throw new IllegalArgumentException("Invalid timeout");
        }
        String body = JsonSerialization.writeValueAsString(Map.of("webhook_token", jwt));
        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "Keycloak-Client-Webhook/1.3-outbox")
                .header("X-Webhook-Event-Id", payload.eventId())
                .header("X-Keycloak-Event-Type", payload.eventType())
                .header("X-Keycloak-Realm", payload.realm())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    public static boolean isRetryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }
}
