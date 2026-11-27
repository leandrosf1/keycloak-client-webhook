package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Minimal durable event snapshot. Never serialize or persist a signed JWT or PII beyond username. */
public final class ClientWebhookOutboxPayloadCodec {
    public static final int MAX_JSON_BYTES = 3500;

    private ClientWebhookOutboxPayloadCodec() {}

    public static String encode(ClientWebhookPayload payload, String issuer) {
        if (payload == null || payload.user() == null || payload.user().id() == null
                || payload.user().username() == null || payload.user().username().isBlank()
                || payload.client() == null || issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Incomplete Outbox payload");
        }
        ClientWebhookPayload safe = new ClientWebhookPayload(payload.eventId(),
                payload.eventType(), payload.occurredAt(), payload.source(), payload.realm(),
                payload.client(), new ClientWebhookPayload.UserInfo(payload.user().id(),
                payload.user().username(), null, null, null), payload.role(), null);
        try {
            String json = JsonSerialization.writeValueAsString(
                    new ClientWebhookOutboxStore.EventData(issuer, safe));
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("Outbox payload is too large");
            }
            return json;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode Outbox payload", e);
        }
    }

    public static ClientWebhookOutboxStore.EventData decode(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Invalid Outbox JSON size");
        }
        try {
            ClientWebhookOutboxStore.EventData data =
                    JsonSerialization.readValue(json, ClientWebhookOutboxStore.EventData.class);
            if (data == null || data.issuer() == null || data.issuer().isBlank()
                    || data.payload() == null) {
                throw new IllegalArgumentException("Invalid Outbox event");
            }
            return data;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid Outbox JSON", e);
        }
    }
}
