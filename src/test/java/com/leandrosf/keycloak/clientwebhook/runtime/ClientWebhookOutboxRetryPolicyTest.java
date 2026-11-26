package com.leandrosf.keycloak.clientwebhook.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientWebhookOutboxRetryPolicyTest {
    @Test void retriesThreeAttemptsThenStartsNewCycle() {
        assertEquals(15, ClientWebhookOutboxRetryPolicy.delaySeconds(1, 3));
        assertEquals(30, ClientWebhookOutboxRetryPolicy.delaySeconds(2, 3));
        assertEquals(300, ClientWebhookOutboxRetryPolicy.delaySeconds(3, 3));
        assertEquals(15, ClientWebhookOutboxRetryPolicy.delaySeconds(4, 3));
        assertEquals(600, ClientWebhookOutboxRetryPolicy.delaySeconds(6, 3));
    }

    @Test void singletonCyclesAndMaximumBackoff() {
        assertEquals(300, ClientWebhookOutboxRetryPolicy.delaySeconds(1, 1));
        assertEquals(86400, ClientWebhookOutboxRetryPolicy.delaySeconds(999, 3));
    }

    @Test void invalidCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientWebhookOutboxRetryPolicy.delaySeconds(0, 3));
        assertThrows(IllegalArgumentException.class, () -> ClientWebhookOutboxRetryPolicy.delaySeconds(1, 0));
    }
}
