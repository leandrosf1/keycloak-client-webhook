package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.config.ClientWebhookConfig;
import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import jakarta.persistence.EntityManager;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;

import java.sql.Clob;
import java.sql.SQLException;
import java.util.List;

/**
 * Oracle 19c implementation of the optional Outbox store. SQL is restricted to
 * KEYCLOAK.KCW_WEBHOOK_OUTBOX and executes inside the caller's transaction.
 */
public final class OracleClientWebhookOutboxStore implements ClientWebhookOutboxStore {
    private static final String TABLE = "KEYCLOAK.KCW_WEBHOOK_OUTBOX";
    private static final int MAX_JSON_BYTES = ClientWebhookOutboxPayloadCodec.MAX_JSON_BYTES;

    private static EntityManager em(KeycloakSession session) {
        JpaConnectionProvider provider = session.getProvider(JpaConnectionProvider.class);
        if (provider == null) throw new IllegalStateException("Keycloak JPA connection unavailable");
        return provider.getEntityManager();
    }

    @Override
    public void append(KeycloakSession session, String realmId, ClientWebhookConfig config,
                       ClientWebhookPayload payload, String issuer) {
        if (realmId == null || realmId.isBlank() || issuer == null || issuer.isBlank()
                || payload == null || payload.user() == null
                || payload.user().id() == null || payload.user().username() == null
                || payload.user().username().isBlank()) {
            throw new IllegalArgumentException("Incomplete webhook Outbox event");
        }
        // The codec enforces data minimization and the Oracle binding limit.
        String json = ClientWebhookOutboxPayloadCodec.encode(payload, issuer);
        em(session).createNativeQuery("INSERT INTO " + TABLE + " ("
                + "EVENT_ID,REALM_ID,REALM_NAME,CLIENT_ID,USER_ID,EVENT_TYPE,"
                + "ENDPOINT_URL,TIMEOUT_MS,MAX_ATTEMPTS_PER_CYCLE,PAYLOAD_JSON) "
                + "VALUES (:eventId,:realmId,:realmName,:clientId,:userId,:eventType,"
                + ":url,:timeout,:maxAttempts,TO_CLOB(:json))")
                .setParameter("eventId", payload.eventId())
                .setParameter("realmId", realmId)
                .setParameter("realmName", payload.realm())
                .setParameter("clientId", payload.client().clientId())
                .setParameter("userId", payload.user().id())
                .setParameter("eventType", payload.eventType())
                .setParameter("url", config.url().toString())
                .setParameter("timeout", config.timeoutMs())
                .setParameter("maxAttempts", config.maxAttempts())
                .setParameter("json", json)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> findDueIds(KeycloakSession session, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid batch limit");
        return em(session).createNativeQuery("SELECT EVENT_ID FROM (SELECT EVENT_ID FROM " + TABLE
                + " WHERE (STATUS='PENDING' AND NEXT_ATTEMPT_AT <= SYSTIMESTAMP)"
                + " OR (STATUS='PROCESSING' AND LOCK_UNTIL < SYSTIMESTAMP)"
                + " ORDER BY NEXT_ATTEMPT_AT, CREATED_AT) WHERE ROWNUM <= :batch")
                .setParameter("batch", limit).getResultList();
    }

    @Override
    public ClaimedEvent claim(KeycloakSession session, String id, String owner, int leaseSeconds) {
        if (leaseSeconds < 90 || leaseSeconds > 3600 || owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Invalid Outbox claim");
        }
        int updated = em(session).createNativeQuery("UPDATE " + TABLE + " SET "
                + "STATUS='PROCESSING', LOCK_OWNER=:owner,"
                + "LOCK_UNTIL=SYSTIMESTAMP+NUMTODSINTERVAL(:leaseSecs,'SECOND'),"
                + "ATTEMPT_COUNT=ATTEMPT_COUNT+1,UPDATED_AT=SYSTIMESTAMP "
                + "WHERE EVENT_ID=:id AND ("
                + "(STATUS='PENDING' AND NEXT_ATTEMPT_AT<=SYSTIMESTAMP)"
                + " OR (STATUS='PROCESSING' AND LOCK_UNTIL<SYSTIMESTAMP))")
                .setParameter("owner", owner).setParameter("leaseSecs", leaseSeconds)
                .setParameter("id", id).executeUpdate();
        if (updated == 0) return null;
        if (updated != 1) throw new IllegalStateException("Outbox claim affected multiple events");
        Object[] row = (Object[]) em(session).createNativeQuery(
                "SELECT EVENT_ID,REALM_ID,REALM_NAME,CLIENT_ID,ENDPOINT_URL,"
                + "TIMEOUT_MS,MAX_ATTEMPTS_PER_CYCLE,ATTEMPT_COUNT,PAYLOAD_JSON "
                + "FROM " + TABLE + " WHERE EVENT_ID=:id AND LOCK_OWNER=:owner "
                + "AND STATUS='PROCESSING'")
                .setParameter("id", id).setParameter("owner", owner).getSingleResult();
        // Do not parse JSON during a claim transaction. Malformed entries can be
        // marked FAILED by the worker instead of becoming permanently stuck.
        return new ClaimedEvent((String) row[0], (String) row[1], (String) row[2],
                (String) row[3], (String) row[4], ((Number) row[5]).intValue(),
                ((Number) row[6]).intValue(), ((Number) row[7]).intValue(),
                clobString(row[8]));
    }

    private static String clobString(Object raw) {
        if (raw instanceof String s) return s;
        if (raw instanceof Clob c) {
            try {
                long length = c.length();
                if (length > MAX_JSON_BYTES) throw new IllegalStateException("Outbox JSON too large");
                return c.getSubString(1, (int) length);
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to read Outbox JSON", e);
            }
        }
        throw new IllegalStateException("Unsupported Oracle CLOB value");
    }

    @Override
    public boolean markDelivered(KeycloakSession session, ClaimedEvent e, String owner, int status) {
        return em(session).createNativeQuery("UPDATE " + TABLE + " SET STATUS='DELIVERED',"
                + "LOCK_OWNER=NULL,LOCK_UNTIL=NULL,DELIVERED_AT=SYSTIMESTAMP,"
                + "LAST_HTTP_STATUS=:http,LAST_ERROR=NULL,UPDATED_AT=SYSTIMESTAMP "
                + "WHERE EVENT_ID=:id AND STATUS='PROCESSING' AND LOCK_OWNER=:owner"
                + " AND ATTEMPT_COUNT=:attemptCount")
                .setParameter("http", status).setParameter("id", e.eventId())
                .setParameter("owner", owner).setParameter("attemptCount", e.attemptCount())
                .executeUpdate() == 1;
    }

    @Override
    public boolean markRetry(KeycloakSession session, ClaimedEvent e, String owner,
                             Integer http, long delaySeconds, String reason) {
        return finish(session, e, owner, "PENDING", http, delaySeconds, reason);
    }

    @Override
    public boolean markFailed(KeycloakSession session, ClaimedEvent e, String owner,
                              Integer http, String reason) {
        return finish(session, e, owner, "FAILED", http, 0L, reason);
    }

    private boolean finish(KeycloakSession session, ClaimedEvent e, String owner,
                           String status, Integer http, long delay, String reason) {
        if (delay < 0 || delay > 86400) throw new IllegalArgumentException("Invalid retry delay");
        String safe = reason == null ? "UNKNOWN" : reason.replaceAll("[^A-Z0-9_]", "_");
        if (safe.length() > 100) safe = safe.substring(0, 100);
        return em(session).createNativeQuery("UPDATE " + TABLE + " SET STATUS=:status,"
                + "LOCK_OWNER=NULL,LOCK_UNTIL=NULL,UPDATED_AT=SYSTIMESTAMP,"
                + "NEXT_ATTEMPT_AT=SYSTIMESTAMP+NUMTODSINTERVAL(:delay,'SECOND'),"
                + "LAST_HTTP_STATUS=CASE WHEN :http=0 THEN NULL ELSE :http END,"
                + "LAST_ERROR=:reason,"
                + "RETRY_CYCLE=RETRY_CYCLE+CASE WHEN :cycle=1 THEN 1 ELSE 0 END "
                + "WHERE EVENT_ID=:id AND STATUS='PROCESSING' AND LOCK_OWNER=:owner"
                + " AND ATTEMPT_COUNT=:attemptCount")
                .setParameter("status", status).setParameter("delay", delay)
                .setParameter("http", http == null ? 0 : http)
                .setParameter("reason", safe)
                .setParameter("cycle", e.attemptCount() % e.maxAttemptsPerCycle() == 0 ? 1 : 0)
                .setParameter("id", e.eventId())
                .setParameter("owner", owner).setParameter("attemptCount", e.attemptCount())
                .executeUpdate() == 1;
    }
}
