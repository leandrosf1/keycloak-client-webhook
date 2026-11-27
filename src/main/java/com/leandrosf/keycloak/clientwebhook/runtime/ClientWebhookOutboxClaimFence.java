package com.leandrosf.keycloak.clientwebhook.runtime;

/**
 * Immutable SQL ownership condition shared by every terminal Outbox transition.
 *
 * Fencing by attempt count ensures that a previously leased worker cannot
 * overwrite another worker's newer claim, even if a future refactor reuses
 * an owner identifier. Oracle must atomically evaluate this WHERE predicate.
 */
final class ClientWebhookOutboxClaimFence {
    static final String WHERE_CURRENT_CLAIM =
            "WHERE EVENT_ID=:id AND STATUS='PROCESSING' "
            + "AND LOCK_OWNER=:owner AND ATTEMPT_COUNT=:attemptCount";

    private ClientWebhookOutboxClaimFence() {}
}
