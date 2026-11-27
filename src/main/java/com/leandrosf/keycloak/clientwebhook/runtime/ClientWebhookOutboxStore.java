package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.config.ClientWebhookConfig;
import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.keycloak.models.KeycloakSession;

import java.util.List;

/**
 * Database-portable contract for the optional durable delivery store.
 * Methods participate in the caller's Keycloak transaction; none commit it.
 */
public interface ClientWebhookOutboxStore {
    record EventData(String issuer, ClientWebhookPayload payload) {}

    record ClaimedEvent(String eventId, String realmId, String realmName,
                        String clientId, String endpointUrl, int timeoutMs,
                        int maxAttemptsPerCycle, int attemptCount, String payloadJson) {}

    void append(KeycloakSession session, String realmId, ClientWebhookConfig config,
                ClientWebhookPayload payload, String issuer);

    List<String> findDueIds(KeycloakSession session, int limit);

    ClaimedEvent claim(KeycloakSession session, String eventId, String owner, int leaseSeconds);

    boolean markDelivered(KeycloakSession session, ClaimedEvent event, String owner, int status);

    boolean markRetry(KeycloakSession session, ClaimedEvent event, String owner,
                      Integer status, long delaySeconds, String reason);

    boolean markFailed(KeycloakSession session, ClaimedEvent event, String owner,
                       Integer status, String reason);
}
