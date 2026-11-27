# Persistent Webhook Outbox — implementation contract (draft)

Status: **DESIGN / NOT DEPLOYED**. Target: Oracle 19c (schema `KEYCLOAK`), Keycloak 26.7.4 and 26.8.2. This document does not activate the Outbox.

## Non-negotiable boundaries

- The provider is optional. Removing its JAR and removing its SPI configuration must leave native Keycloak authentication, authorization, administration, MFA, OIDC and backchannel logout operational after the documented rebuild/restart.
- Do not patch native Keycloak classes, tables, Liquibase changelogs or authentication flows. Use only supported/available extension points and the provider-owned table `KEYCLOAK.KCW_WEBHOOK_OUTBOX`.
- Deliveries run **outside** the administrative transaction. Events are persisted in the same database transaction as the monitored operation; on rollback, the Outbox insert must roll back too.
- The table is already created in homologation. Do not apply DDL automatically from the JAR.
- Activation is explicit and **off by default**. Never run the legacy dispatcher and Outbox sender for the same event.
- When uninstalling, pending Outbox events are *not* delivered automatically. Reconcile pending records and warn dependent federated applications before removal.

## Failure-policy decision (must be explicitly accepted before activation)

The provider cannot both **guarantee an Outbox entry for every successful role revocation** and **guarantee that every native role revocation succeeds while the Outbox database write fails**. Choose and test one policy for monitored administrative events:

- **Transactional fail-closed:** a write failure rejects/rolls back the monitored change. Operationally this may block emergency access revocation.
- **Availability-first:** the monitored change succeeds, but delivery can be lost; requires independent reconciliation/compensating controls.

Neither policy should be silently assumed for production. Native LOGIN/MFA events must not fail merely due to an Outbox write error.

## Outbox processing invariants

1. Each event has one stable `EVENT_ID` (also JWT `jti`) and original `occurred_at`.
2. Persist only minimal signed-event inputs. Do not store a previously signed JWT; generate a fresh RS256 signature at send time, with new `iat`, `nbf`, and `exp` (five-minute validity).
3. An atomic claim is committed before HTTP. A lease allows recovery after a node dies. Completion uses compare-and-set ownership; a competing worker must not overwrite it.
4. Delivery is **at-least-once**, not exactly-once. The recipient must deduplicate by event ID and must remain idempotent. A worker can complete the remote HTTP operation after its lease expires; a second worker may then deliver the same `EVENT_ID`. Never interpret a committed claim as exactly-once delivery.
5. Acknowledgement and retry/failure transitions require the **event ID, current owner and exact attempt count**, preventing completion by a previously leased worker after a newer claim. When a final update affects zero rows, log lost ownership rather than claiming the Outbox was delivered.
6. A 120-second lease currently exceeds the 60-second maximum HTTP request timeout, but this does not guarantee the entire signing/transaction/HTTP flow will always complete before lease expiry. Validate under slow Oracle connections, overloaded nodes and process pauses.
5. HTTP 2xx acknowledges delivery; temporary errors (408, 429, 5xx, timeouts) are retried with bounded backoff; permanent errors have an explicit terminal/review status.
6. A destination/configuration change must have defined semantics for events already queued. The initial design snapshots endpoint and delivery settings, and documents the consequences.
7. Workers use bounded batches, polling and network timeouts. No worker is started when the feature is disabled. No SQL is executed against the Outbox in disabled mode.
8. Outbound destinations need HTTPS in production and egress/DNS restrictions. Never write JWTs or sensitive event payloads to logs.

## Tests required before merge

- `mvn clean verify` for both Keycloak 26.7.4 and 26.8.2 in GitHub Actions.
- Listener with feature disabled: legacy contract retained and no Outbox read/write.
- Monitored role grant, revoke and user removal: persisted on commit; none after rollback; exactly one Outbox insert per configured receiver.
- Failure to insert: explicit configured failure policy, validated against actual Keycloak transactions and the APEX administrative flow.
- Non-monitored events, login and MFA: unaffected under Outbox outage.
- Worker claiming/concurrency across **two Keycloak nodes** on Oracle 19c, including lease expiry, re-claim of `PROCESSING`, and a stale owner's attempt to finalize after a new owner has claimed the row (must update **zero** rows).
- Slow/paused worker resumes after lease expiry: verify duplicate remote delivery is possible but cannot overwrite the newer claim, and receiver deduplicates the stable event ID.
- Verify both `markDelivered`, `markRetry` and `markFailed` use the current owner + attempt fence; the basic unit test checks only the shared SQL predicate, not Oracle concurrency.
- HTTP 204, 400, 408, 429, 503, timeout and connection failure; restart with pending and in-flight events.
- Preserve eventId on retry; fresh JWT temporal claims; verify recipient signature, issuer and audience.
- Install, disable, rebuild, remove JAR, rebuild and retest native authentication, user creation, role changes, OIDC and MFA.

## Release safeguards

- Test with a dedicated Oracle homologation database; no production credentials or private certificates in GitHub/CI.
- Measure SQL load and maintain retention, monitoring and alerting for `FAILED`/`PENDING` events.
- Only merge into `main` after review, passing tests and explicit approval. JAR installation and production activation are separate decisions.
