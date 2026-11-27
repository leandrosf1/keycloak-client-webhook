# Oracle Outbox — homologation checklist (NOT production instructions)

**Status:** Three operator-observed homologation scenarios succeeded on 2026-11-27 (Keycloak 26.8.2 / Oracle 19c / eSegTrab): normal ROLE_REVOKED delivery, receiver-outage retry, and delivery after restarting Keycloak with an event pending. Transaction rollback, multi-node concurrency, insert-failure handling and uninstall scenarios remain unverified. The feature defaults to OFF; do not enable on production. Owner approval is required before merge and again before deployment.

## Prerequisites

- Keycloak 26.7.4 or 26.8.2 in a disposable homologation environment; test both versions separately.
- Oracle Database 19c, `KEYCLOAK.KCW_WEBHOOK_OUTBOX` already created, three indexes present, and table initially empty.
- A dedicated test client with ROLE_REVOKED, ROLE_GRANTED and USER_REMOVED webhook configuration; isolated HTTPS receiver returning configurable HTTP responses.
- A known-good backup/recovery process and a way to test two Keycloak nodes sharing the same Oracle database.
- Do not use real CPF, live user accounts, production secrets or real application endpoints.

## Baseline: Outbox OFF

1. Install the experimental **1.3.0-SNAPSHOT** JAR in homologation, rebuild Keycloak using its standard provider deployment procedure and start without any `outbox-enabled` flag.
2. Confirm the startup log says the Outbox is disabled.
3. Verify login, MFA, OIDC, backchannel logout, APEX user creation, role grants and revocations, and user deletion behave as with the prior provider.
4. Verify the table remains unchanged; no Outbox worker must run.
5. Verify the signed JWT receiver contract has not changed.

## Outbox ON (administrative events only)

Set provider-specific options on the homologation server, verifying they are actually read at runtime:

```properties
spi-events-listener--client-webhook-runtime--outbox-enabled=true
spi-events-listener--client-webhook-runtime--outbox-poll-ms=3000
spi-events-listener--client-webhook-runtime--outbox-batch-size=10
```

Rebuild/restart according to the distribution startup mode; never leave two JAR versions installed.

Execute each scenario and record logs, database evidence, receiver calls and Keycloak outcomes:

| Test | Expected outcome |
| --- | --- |
| Revoke configured client role | Same transaction inserts one PENDING event, later DELIVERED on 204 |
| Grant configured client role | Same contract as revocation |
| Remove user with two subscribed clients | One event per subscribed receiver, consistent rollback on failed change |
| Roll back the role change | No committed Outbox row and no outgoing HTTP |
| Unconfigured client or event | No Outbox insert and no webhook call |
| Return 503, 429, 408 or timeout | Persisted retry with stable event ID and new JWT timestamps |
| Return 400 | FAILED and visible for operator review; no automatic endless retry |
| Restart after PENDING | Unsent event picked up after restart |
| Stop node after HTTP but before state update | Recipient deduplicates redelivery by event ID |
| Run two nodes | One owner at a time; a crashed owner recovers after lease expiration |
| Make Outbox INSERT fail | Explicit fail-closed behavior for monitored admin operation; no partial role change; other events unaffected |
| Login/MFA while Outbox is unavailable | Authentication is not rolled back by Outbox errors; nonadmin events still use legacy sender |
| Disable feature with PENDING events | Worker stops; queued events are preserved and require reconciliation |
| Remove JAR, remove explicit SPI options, rebuild | Native KC operations still work; no webhook notifications |

**Do not assume** transaction rollback or the Oracle SQL dialect is correct based only on unit tests. Observe real database state after both the success and rollback paths. Verify that changing an endpoint does not redirect events already queued (destination is snapshotted).

## Homologation evidence — 2026-11-27

**Observed by the environment operator (not reproduced in GitHub CI):**

- Keycloak 26.8.2 started with Outbox ENABLED and without the earlier recurring SQL poll exception, after the Outbox table was provisioned in the database/service used by the Keycloak connection.
- An intentional test `ROLE_REVOKED` for the eSegTrab client was persisted and dispatched to the configured receiver.
- The operator confirmed that the receiving application recorded the event and ended the local access.
- Oracle Outbox row showed `STATUS=DELIVERED`, `ATTEMPT_COUNT=1`, `LAST_HTTP_STATUS=204`, and cleared `LOCK_OWNER` / `LOCK_UNTIL`. Created 09:33:28 and delivered 09:33:31 (local timezone).
- No user IDs, usernames, internal endpoint URLs, raw JWTs or event IDs have been copied into this public repository.

### Receiver outage and recovery — operator-reported, 2026-11-27

- The operator stopped the eSegTrab service to make the configured webhook endpoint unavailable.
- A test client role was revoked in Keycloak while the receiver was unavailable. The role revocation committed and an Outbox record was persisted.
- The operator observed `DELIVERY_EXCEPTION` as the failure reason during delivery attempts and reported **six attempts across two retry cycles** while the service remained unavailable.
- After restarting eSegTrab, the previously queued event was delivered: the receiving system ended access, recorded the event, and the Outbox record transitioned to `DELIVERED`.
- No restart of Keycloak was reported as part of this test. The final HTTP status and final ATTEMPT_COUNT were not separately supplied for this outage scenario.
- In the current Java implementation, `DELIVERY_EXCEPTION` is a `LAST_ERROR` reason; the retryable row's `STATUS` is `PENDING`, then becomes `DELIVERED` on success. This distinction should be verified in the database during future tests.

**Scope:** This demonstrates successful persistent retries and eventual recovery after the *receiver* was offline, as observed in homologation. The separate Keycloak restart test below covers process restart, but **neither scenario** establishes transaction rollback, multi-node exclusivity or production readiness.

### Keycloak restart with pending event — operator-reported, 2026-11-27

- The operator stopped the eSegTrab service and revoked a test role in Keycloak.
- The row was persisted in the Oracle Outbox and remained `PENDING` because the receiver was unavailable.
- The Keycloak server was stopped, left offline for a period, and then started again with the Outbox enabled.
- After restarting, the worker attempted delivery; the record remained pending with `LAST_ERROR=DELIVERY_EXCEPTION` while the receiver was still unavailable.
- The operator restarted eSegTrab. Once `NEXT_ATTEMPT_AT` was reached, the pending event was delivered; the receiving system ended the user's local access and recorded the event, and the Outbox changed to `DELIVERED`.
- This confirms recovery after a full Keycloak restart in this single-node homologation scenario. The event ID was not explicitly compared before and after the restart, so cross-restart ID continuity is inferred from the row progression and should be explicitly verified in another test.
- No production data, user identifiers, event identifiers, endpoint URLs or JWTs are included in this public log.

**Release note:** Still no evidence for rollback-on-error behavior, cluster concurrency, expired leases, duplicate suppression under races, or safe uninstall. Do not merge or activate in production solely on these successful tests.

- [x] Receiver outage (service stopped) → repeated delivery failures with persisted retry cycles → receiver restarted → `DELIVERED` and access ended (operator-observed, 2026-11-27).
- [x] Restart Keycloak with an outstanding `PENDING` event; operator confirmed the queued event eventually delivered after restarting both Keycloak and the receiver (2026-11-27).
- [ ] Test rollback, error policy for INSERT failure, multi-node race/lease recovery, duplicate delivery, and safe uninstall.

## SQL schema consistency preflight (required before enabling)

The initial table and database service must match the Oracle adapter. In this homologation environment the operator confirmed that all 21 expected columns exist, and one delivery completed. **Every new environment must independently verify schema and database-service alignment before activation.** A Maven build does not validate SQL table structure.

Run these read-only statements against the **same database/service used by the Keycloak datasource**:

```sql
SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AS CURRENT_SCHEMA,
       SYS_CONTEXT('USERENV','DB_NAME') AS DATABASE_NAME
FROM DUAL;

SELECT OWNER, TABLE_NAME
FROM ALL_TABLES
WHERE OWNER = 'KEYCLOAK' AND TABLE_NAME = 'KCW_WEBHOOK_OUTBOX';

SELECT COLUMN_ID, COLUMN_NAME, DATA_TYPE, NULLABLE, DATA_DEFAULT
FROM ALL_TAB_COLUMNS
WHERE OWNER='KEYCLOAK' AND TABLE_NAME='KCW_WEBHOOK_OUTBOX'
ORDER BY COLUMN_ID;

SELECT COUNT(*) AS TOTAL
FROM KEYCLOAK.KCW_WEBHOOK_OUTBOX;
```

The experimental adapter currently references extra columns such as `REALM_NAME`, `ENDPOINT_URL`, `TIMEOUT_MS`, `MAX_ATTEMPTS_PER_CYCLE`, `UPDATED_AT`, `LAST_HTTP_STATUS` and `RETRY_CYCLE`. These **must not be presumed to exist** in the first migration. Do not change schema manually or run ALTER TABLE before producing a reviewed migration (or adjusting the adapter to the original schema).

If the poller reports a JDBC error code, investigate its Oracle `ORA-` meaning before assuming the cause. An `SQLGrammarException` alone cannot distinguish missing objects, columns or permissions.

## Read-only monitoring SQL

```sql
SELECT STATUS, COUNT(*) AS TOTAL
FROM KEYCLOAK.KCW_WEBHOOK_OUTBOX
GROUP BY STATUS ORDER BY STATUS;

SELECT EVENT_ID, EVENT_TYPE, CLIENT_ID, STATUS,
       ATTEMPT_COUNT, RETRY_CYCLE, NEXT_ATTEMPT_AT,
       LOCK_UNTIL, LAST_HTTP_STATUS, LAST_ERROR
FROM KEYCLOAK.KCW_WEBHOOK_OUTBOX
WHERE STATUS <> 'DELIVERED'
ORDER BY CREATED_AT DESC
FETCH FIRST 50 ROWS ONLY;
```

Never publish the contents of `PAYLOAD_JSON` or the signed JWT in a public GitHub Actions log or issue.

## Blocking release criteria

- Successful Maven/CI verification against 26.7.4 and 26.8.2.
- Actual transaction/rollback and multi-node/lease tests with Oracle 19c.
- Receiver validates signatures, audience, issuer, expiry and event idempotency.
- Database-error operational policy explicitly approved (a failing INSERT may block urgent role revocation).
- Performance, queue backlog monitoring and retention strategy approved.
- README matches the final behavior and version.
- Explicit owner approval for PR merge, then a separate approval for production activation.
