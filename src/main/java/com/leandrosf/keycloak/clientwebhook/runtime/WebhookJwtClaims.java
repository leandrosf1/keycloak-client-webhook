package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable JWT claim snapshot. Actionable data lives only inside the signature.
 * No Keycloak server dependencies: safe to unit-test independently.
 */
public final class WebhookJwtClaims {

    public static final long LIFETIME_SECONDS = 300L;
    public static final String TOKEN_USE = "client_webhook";

    private WebhookJwtClaims() {
    }

    public static Map<String, Object> create(
            ClientWebhookPayload event, String issuer, long issuedAtEpochSeconds) {

        if (event == null || event.client() == null || event.user() == null) {
            throw new IllegalArgumentException("Webhook event, client and user are required");
        }
        require(issuer, "issuer");
        require(event.eventId(), "eventId");
        require(event.eventType(), "eventType");
        require(event.realm(), "realm");
        require(event.client().clientId(), "clientId");
        require(event.user().id(), "userId");
        require(event.user().username(), "username");
        if (("ROLE_REVOKED".equals(event.eventType())
                || "ROLE_GRANTED".equals(event.eventType()))
                && (event.role() == null || event.role().name() == null
                    || event.role().name().isBlank())) {
            throw new IllegalArgumentException("Role event must identify its role");
        }
        if (issuedAtEpochSeconds <= 0 || issuedAtEpochSeconds > Long.MAX_VALUE - LIFETIME_SECONDS) {
            throw new IllegalArgumentException("Invalid issued-at time");
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", event.client().clientId());
        claims.put("sub", event.user().id());
        claims.put("iat", issuedAtEpochSeconds);
        claims.put("nbf", issuedAtEpochSeconds);
        claims.put("exp", issuedAtEpochSeconds + LIFETIME_SECONDS);
        claims.put("jti", event.eventId());
        claims.put("token_use", TOKEN_USE);
        claims.put("event_type", event.eventType());
        claims.put("occurred_at", event.occurredAt());
        claims.put("source", event.source());
        claims.put("realm", event.realm());
        claims.put("client_id", event.client().clientId());
        claims.put("client", event.client());
        claims.put("user", event.user());
        if (event.role() != null) {
            claims.put("role", event.role());
        }
        // Omit IP and OIDC session identifiers from signed webhook claims.
        return Map.copyOf(claims);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required webhook claim: " + field);
        }
    }
}
