# Oracle Outbox — homologation checklist (NOT production instructions)

**Status:** Not executed against a running Keycloak/Oracle environment. The feature is OFF by default. Do not enable on production. Owner approval is required before merge and again before deployment.

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
