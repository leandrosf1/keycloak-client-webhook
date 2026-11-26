package com.leandrosf.keycloak.clientwebhook.runtime;

/**
 * Bounded backoff for a durable Outbox. Retries are not discarded when one cycle ends.
 * This class has no dependency on Keycloak runtime and is safe to unit test.
 */
public final class ClientWebhookOutboxRetryPolicy {
    private ClientWebhookOutboxRetryPolicy() {}

    public static long delaySeconds(int attemptCount, int perCycle) {
        if (attemptCount < 1 || perCycle < 1) {
            throw new IllegalArgumentException("Invalid attempts");
        }
        int withinCycle = (attemptCount - 1) % perCycle;
        int completedCycles = attemptCount / perCycle;
        if (withinCycle < perCycle - 1) {
            return Math.min(300L, 15L << Math.min(withinCycle, 4));
        }
        return Math.min(86400L, 300L << Math.min(completedCycles - 1, 9));
    }
}
