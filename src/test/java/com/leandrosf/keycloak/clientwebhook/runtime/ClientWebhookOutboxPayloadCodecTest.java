package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientWebhookOutboxPayloadCodecTest {
    private ClientWebhookPayload event() {
        return new ClientWebhookPayload("f94be9bb-3554-4d89-970a-2da225d94d67",
                "ROLE_REVOKED", "2026-11-26T12:00:00Z", "PROVIDER_EVENT",
                "portal-acesso", new ClientWebhookPayload.ClientInfo("kc-client-id","java_esegtrab"),
                new ClientWebhookPayload.UserInfo("user-id","12345678901",
                        "private@example.invalid","FirstSecret","LastSecret"),
                new ClientWebhookPayload.RoleInfo("role-id","role_java_esegtrab"),
                new ClientWebhookPayload.ContextInfo("192.0.2.10","secret-session-id"));
    }

    @Test void roundTripContainsOnlyNecessaryUserFields() {
        String json = ClientWebhookOutboxPayloadCodec.encode(event(), "https://sso.invalid/realms/portal-acesso");
        assertFalse(json.contains("private@example.invalid"));
        assertFalse(json.contains("FirstSecret"));
        assertFalse(json.contains("LastSecret"));
        assertFalse(json.contains("192.0.2.10"));
        assertFalse(json.contains("secret-session-id"));
        var parsed = ClientWebhookOutboxPayloadCodec.decode(json);
        assertEquals(event().eventId(), parsed.payload().eventId());
        assertEquals("12345678901", parsed.payload().user().username());
        assertNull(parsed.payload().user().email());
        assertNull(parsed.payload().context());
        assertEquals("https://sso.invalid/realms/portal-acesso", parsed.issuer());
    }

    @Test void rejectsMalformedAndOversizedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientWebhookOutboxPayloadCodec.decode("{malformed"));
        assertThrows(IllegalArgumentException.class,
                () -> ClientWebhookOutboxPayloadCodec.decode("x".repeat(4000)));
        assertThrows(IllegalArgumentException.class,
                () -> ClientWebhookOutboxPayloadCodec.encode(event(), ""));
    }
}
