package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.config.ClientWebhookTransportPolicy;
import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded background worker for opt-in administrative Outbox events.
 * Owns no Keycloak request session; every persistence operation opens a new
 * short-lived transaction. HTTP is always performed after a committed claim.
 */
public final class ClientWebhookOutboxWorker implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ClientWebhookOutboxWorker.class);
    private static final int LEASE_SECONDS = 120;

    private final KeycloakSessionFactory sessions;
    private final ClientWebhookOutboxStore store;
    private final ClientWebhookOutboxHttpSender sender = new ClientWebhookOutboxHttpSender();
    private final ScheduledExecutorService scheduler;
    private final String workerId = UUID.randomUUID().toString();
    private final int pollMs;
    private final int batchSize;

    public ClientWebhookOutboxWorker(KeycloakSessionFactory sessions,
                                    ClientWebhookOutboxStore store, int pollMs, int batchSize) {
        this.sessions = sessions;
        this.store = store;
        this.pollMs = pollMs;
        this.batchSize = batchSize;
        AtomicInteger count = new AtomicInteger();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "kcw-outbox-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::pollSafely, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            List<String> due = tx(session -> store.findDueIds(session, batchSize));
            for (String id : due) {
                if (Thread.currentThread().isInterrupted()) return;
                String owner = workerId + ":" + UUID.randomUUID();
                ClientWebhookOutboxStore.ClaimedEvent claimed =
                        tx(session -> store.claim(session, id, owner, LEASE_SECONDS));
                if (claimed != null) deliver(claimed, owner);
            }
        } catch (Exception e) {
            LOG.errorf("Outbox polling error (%s); will retry next poll", e.getClass().getSimpleName());
        }
    }

    private void deliver(ClientWebhookOutboxStore.ClaimedEvent e, String owner) {
        final ClientWebhookOutboxStore.EventData data;
        final URI endpoint;
        try {
            data = JsonSerialization.readValue(e.payloadJson(), ClientWebhookOutboxStore.EventData.class);
            endpoint = URI.create(e.endpointUrl());
            if (data == null || data.payload() == null || data.issuer() == null
                    || !ClientWebhookTransportPolicy.isAllowedEndpoint(endpoint)
                    || !validEnvelope(e, data.payload()) || e.timeoutMs() < 250
                    || e.timeoutMs() > 60000 || e.maxAttemptsPerCycle() < 1
                    || e.maxAttemptsPerCycle() > 5) {
                markFailed(e, owner, null, "INVALID_STORED_EVENT");
                return;
            }
        } catch (IOException | IllegalArgumentException | NullPointerException ex) {
            markFailed(e, owner, null, "INVALID_STORED_EVENT");
            return;
        }
        try {
            String jwt = tx(session -> {
                RealmModel realm = session.realms().getRealm(e.realmId());
                if (realm == null || !e.realmName().equals(realm.getName())) {
                    throw new IllegalStateException("REALM_NOT_AVAILABLE");
                }
                session.getContext().setRealm(realm);
                return WebhookJwtSigner.signWithIssuer(session, realm, data.payload(), data.issuer());
            });
            int status = sender.send(endpoint, e.timeoutMs(), data.payload(), jwt);
            if (status >= 200 && status <= 299) {
                boolean updated = tx(session -> store.markDelivered(session, e, owner, status));
                if (updated) LOG.infof("Outbox delivered: eventId=%s client=%s status=%d",
                        e.eventId(), e.clientId(), status);
            } else if (ClientWebhookOutboxHttpSender.isRetryable(status)) {
                markRetry(e, owner, status, "RETRYABLE_HTTP_STATUS");
            } else {
                markFailed(e, owner, status, "PERMANENT_HTTP_STATUS");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Unfinished claim is recovered when the lease expires.
        } catch (Exception ex) {
            LOG.warnf("Outbox delivery deferred: eventId=%s errorType=%s",
                    e.eventId(), ex.getClass().getSimpleName());
            markRetry(e, owner, null, "DELIVERY_EXCEPTION");
        }
    }

    private static boolean validEnvelope(ClientWebhookOutboxStore.ClaimedEvent e,
                                         ClientWebhookPayload p) {
        return p.client() != null && p.user() != null
                && e.eventId().equals(p.eventId())
                && e.clientId().equals(p.client().clientId())
                && e.realmName().equals(p.realm())
                && p.user().id() != null && p.user().username() != null;
    }

    private void markRetry(ClientWebhookOutboxStore.ClaimedEvent e, String owner,
                           Integer status, String reason) {
        long delay = ClientWebhookOutboxRetryPolicy.delaySeconds(
                e.attemptCount(), e.maxAttemptsPerCycle());
        boolean updated = tx(session -> store.markRetry(session, e, owner, status, delay, reason));
        if (updated) LOG.warnf("Outbox retry scheduled: eventId=%s attempt=%d delaySec=%d",
                e.eventId(), e.attemptCount(), delay);
    }

    private void markFailed(ClientWebhookOutboxStore.ClaimedEvent e, String owner,
                            Integer status, String reason) {
        boolean updated = tx(session -> store.markFailed(session, e, owner, status, reason));
        if (updated) LOG.warnf("Outbox event requires review: eventId=%s reason=%s", e.eventId(), reason);
    }

    @FunctionalInterface
    private interface Transaction<T> { T run(KeycloakSession session); }

    private <T> T tx(Transaction<T> action) {
        try (KeycloakSession session = sessions.create()) {
            session.getTransactionManager().begin();
            try {
                T value = action.run(session);
                session.getTransactionManager().commit();
                return value;
            } catch (RuntimeException | Error ex) {
                if (session.getTransactionManager().isActive()) {
                    session.getTransactionManager().rollback();
                }
                throw ex;
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
