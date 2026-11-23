package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookJwtClaimsTest {
    private static final long NOW = 1800000000L;

    private static ClientWebhookPayload payload(String id, String type) {
        return new ClientWebhookPayload(
                id, type, "2026-11-12T12:00:00Z", "PROVIDER_EVENT",
                "portal-acesso", new ClientWebhookPayload.ClientInfo("internal-client", "java_esegtrab"),
                new ClientWebhookPayload.UserInfo("user-uuid", "12345678901", null, null, null),
                "ROLE_REVOKED".equals(type)
                    ? new ClientWebhookPayload.RoleInfo("role-uuid", "role_java_esegtrab") : null,
                null);
    }

    @Test
    void bindsIdentityAudiencePurposeAndExpiry() {
        Map<String, Object> claims = WebhookJwtClaims.create(
                payload("evt-1", "ROLE_REVOKED"), "https://sso.example.org/realms/portal-acesso", NOW);
        assertEquals("java_esegtrab", claims.get("aud"));
        assertEquals("java_esegtrab", claims.get("client_id"));
        assertEquals("user-uuid", claims.get("sub"));
        assertEquals("evt-1", claims.get("jti"));
        assertEquals("client_webhook", claims.get("token_use"));
        assertEquals("ROLE_REVOKED", claims.get("event_type"));
        assertEquals(NOW, claims.get("iat"));
        assertEquals(NOW + 300, claims.get("exp"));
        assertEquals(Map.of("id", "user-uuid", "username", "12345678901"), claims.get("user"));
        assertEquals("role_java_esegtrab", ((ClientWebhookPayload.RoleInfo) claims.get("role")).name());
        assertThrows(UnsupportedOperationException.class, () -> claims.put("aud", "other-client"));
    }

    @Test
    void removesEmailFirstNameAndLastNameEvenWhenPresentInSourceEvent() throws Exception {
        ClientWebhookPayload eventWithProfile = new ClientWebhookPayload(
                "evt-profile", "ROLE_REVOKED", "2026-11-23T12:26:09Z",
                "PROVIDER_EVENT", "portal-acesso",
                new ClientWebhookPayload.ClientInfo("internal-client", "java_esegtrab"),
                new ClientWebhookPayload.UserInfo(
                        "user-uuid", "12345678901",
                        "private@example.test", "SENSITIVE_FIRST_NAME", "SENSITIVE_LAST_NAME"),
                new ClientWebhookPayload.RoleInfo("role-uuid", "role_java_esegtrab"), null);

        Map<String, Object> claims = WebhookJwtClaims.create(
                eventWithProfile, "https://sso.example.org/realms/portal-acesso", NOW);

        assertEquals(Map.of("id", "user-uuid", "username", "12345678901"),
                claims.get("user"));
        String serialized = org.keycloak.util.JsonSerialization.writeValueAsString(claims);
        assertFalse(serialized.contains("private@example.test"));
        assertFalse(serialized.contains("SENSITIVE_FIRST_NAME"));
        assertFalse(serialized.contains("SENSITIVE_LAST_NAME"));
        assertFalse(serialized.contains("\"email\""));
        assertFalse(serialized.contains("\"firstName\""));
        assertFalse(serialized.contains("\"lastName\""));
        assertTrue(serialized.contains("\"username\""));
        assertTrue(serialized.contains("\"user-uuid\""));
    }

    @Test
    void preservesEventIdAcrossRetriesAndRejectsMissingClaims() {
        assertEquals(
                WebhookJwtClaims.create(payload("same-id", "ROLE_REVOKED"), "https://issuer", NOW).get("jti"),
                WebhookJwtClaims.create(payload("same-id", "ROLE_REVOKED"), "https://issuer", NOW).get("jti"));
        assertThrows(IllegalArgumentException.class,
                () -> WebhookJwtClaims.create(payload("evt", "ROLE_REVOKED"), "", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> WebhookJwtClaims.create(payload("", "ROLE_REVOKED"), "https://issuer", NOW));
        ClientWebhookPayload originalNoUsername = payload("evt", "LOGIN");
        ClientWebhookPayload missingUsername = new ClientWebhookPayload(
                originalNoUsername.eventId(), originalNoUsername.eventType(),
                originalNoUsername.occurredAt(), originalNoUsername.source(),
                originalNoUsername.realm(), originalNoUsername.client(),
                new ClientWebhookPayload.UserInfo("user-uuid", null, null, null, null),
                null, null);
        assertThrows(IllegalArgumentException.class,
                () -> WebhookJwtClaims.create(missingUsername, "https://issuer", NOW));
        ClientWebhookPayload original = payload("evt", "LOGIN");
        ClientWebhookPayload malformed = new ClientWebhookPayload(
                original.eventId(), "ROLE_REVOKED", original.occurredAt(),
                original.source(), original.realm(), original.client(), original.user(), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> WebhookJwtClaims.create(malformed, "https://issuer", NOW));
    }
}
