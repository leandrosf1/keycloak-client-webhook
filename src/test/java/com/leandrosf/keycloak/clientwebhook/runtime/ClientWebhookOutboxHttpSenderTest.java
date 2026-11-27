package com.leandrosf.keycloak.clientwebhook.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientWebhookOutboxHttpSenderTest {
    @Test void retryableStatuses() {
        assertTrue(ClientWebhookOutboxHttpSender.isRetryable(408));
        assertTrue(ClientWebhookOutboxHttpSender.isRetryable(429));
        assertTrue(ClientWebhookOutboxHttpSender.isRetryable(500));
        assertTrue(ClientWebhookOutboxHttpSender.isRetryable(503));
        assertFalse(ClientWebhookOutboxHttpSender.isRetryable(204));
        assertFalse(ClientWebhookOutboxHttpSender.isRetryable(302));
        assertFalse(ClientWebhookOutboxHttpSender.isRetryable(400));
        assertFalse(ClientWebhookOutboxHttpSender.isRetryable(404));
    }
    @Test void rejectsUnsignedRequestsBeforeNetwork() {
        ClientWebhookOutboxHttpSender sender = new ClientWebhookOutboxHttpSender();
        assertThrows(IllegalArgumentException.class,
                () -> sender.send(java.net.URI.create("https://example.invalid"), 1000, null, ""));
    }
}
