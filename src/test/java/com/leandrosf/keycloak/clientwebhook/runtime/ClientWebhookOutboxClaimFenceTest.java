package com.leandrosf.keycloak.clientwebhook.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level invariants only. Oracle race/lease tests still require a real
 * Oracle 19c database with two independent Keycloak worker processes.
 */
class ClientWebhookOutboxClaimFenceTest {
    @Test
    void completionRequiresOwnerAndExactAttempt() {
        String predicate = ClientWebhookOutboxClaimFence.WHERE_CURRENT_CLAIM;
        assertTrue(predicate.contains("EVENT_ID=:id"));
        assertTrue(predicate.contains("STATUS='PROCESSING'"));
        assertTrue(predicate.contains("LOCK_OWNER=:owner"));
        assertTrue(predicate.contains("ATTEMPT_COUNT=:attemptCount"));
        assertFalse(predicate.contains(" OR "));
    }
}
