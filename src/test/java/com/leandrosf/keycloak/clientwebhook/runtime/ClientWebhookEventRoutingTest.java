package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientWebhookEventRoutingTest {

    private ClientWebhookPayload payload(String eventType, String source) {
        return new ClientWebhookPayload("event-id", eventType, "2026-11-26T12:00:00Z",
                source, "portal-acesso",
                new ClientWebhookPayload.ClientInfo("client-uuid", "java_esegtrab"),
                new ClientWebhookPayload.UserInfo("user-uuid", "test-user", null, null, null),
                null, null);
    }

    @Test
    void administrativeProviderEventsUseOutbox() {
        assertTrue(ClientWebhookEventListenerFactory.shouldPersistInOutbox(
                payload("ROLE_GRANTED", ClientWebhookPayload.SOURCE_PROVIDER_EVENT)));
        assertTrue(ClientWebhookEventListenerFactory.shouldPersistInOutbox(
                payload("ROLE_REVOKED", ClientWebhookPayload.SOURCE_PROVIDER_EVENT)));
        assertTrue(ClientWebhookEventListenerFactory.shouldPersistInOutbox(
                payload("USER_REMOVED", ClientWebhookPayload.SOURCE_PROVIDER_EVENT)));
    }

    @Test
    void nativeUserEventsStayOnLegacyPostCommitPath() {
        for (String event : new String[]{"LOGIN", "LOGOUT", "RESET_PASSWORD",
                "UPDATE_PROFILE", "VERIFY_EMAIL", "UPDATE_CREDENTIAL"}) {
            assertFalse(ClientWebhookEventListenerFactory.shouldPersistInOutbox(
                    payload(event, ClientWebhookPayload.SOURCE_USER_EVENT)), event);
        }
    }

    @Test
    void wrongEventSourceAndUnknownEventsDoNotEnterOutbox() {
        assertFalse(ClientWebhookEventListenerFactory.shouldPersistInOutbox(
                payload("ROLE_REVOKED", ClientWebhookPayload.SOURCE_USER_EVENT)));
        assertFalse(ClientWebhookEventListenerFactory.shouldPersistInOutbox(
                payload("LOGIN", ClientWebhookPayload.SOURCE_PROVIDER_EVENT)));
        assertFalse(ClientWebhookEventListenerFactory.shouldPersistInOutbox(
                payload("UNKNOWN", ClientWebhookPayload.SOURCE_PROVIDER_EVENT)));
        assertFalse(ClientWebhookEventListenerFactory.shouldPersistInOutbox(null));
    }
}
