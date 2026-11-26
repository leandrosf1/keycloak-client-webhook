# Keycloak Client Webhook

A **removable Keycloak extension** that configures webhooks **per client and per event** in the Keycloak Admin Console. Integrated applications receive an authenticated notification when selected user or client-role events occur.

> **Repository status (November 2026)**
>
> - **`main`: `1.2.0-SNAPSHOT`**, including the signed **RS256 webhook JWT** introduced by PR [#1](https://github.com/leandrosf1/keycloak-client-webhook/pull/1) (merged November 23, 2026). The previous unsigned-JSON/1.1.1 description is **not** the current code contract.
> - **`feature/oracle-webhook-outbox`: work in progress.** CI verification, design notes and small Outbox support classes/tests have been added; the Oracle repository, worker and transaction-listener integration are **not implemented or enabled yet**. The delivery mechanism remains **in memory**.
> - **Do not use this development branch's JAR in production** until all acceptance tests, receiver compatibility checks and homologation have passed.
>
> The project uses Keycloak SPIs, including internal APIs. Validate every target Keycloak release before deploying.

## What it does

- Registers a declarative **Clients Webhook** page in the Admin Console and persists each configuration as a Keycloak realm `ComponentModel`.
- Allows an enabled configuration per client with a selection of events, client-role filters, destination URI, timeout and attempts.
- Monitors `ROLE_REVOKED` and `ROLE_GRANTED` for direct client-role operations; supports `USER_REMOVED` broadcast to subscribed clients and selected native user events.
- Sends requests **after successful transaction commit**; discards them on rollback.
- Signs a `webhook+jwt` token with the emitting realm's active **RS256** signing key. Receivers **must verify** the signed JWT.
- Generates a unique event ID / JWT `jti` for each recipient delivery, enabling receiver-side idempotency.
- Retries transient errors while the process remains alive; there is **no durable queue** in the current runtime.

The provider is **optional**: removing its JAR and rebuilding Keycloak must not require changes to Keycloak's native code, tables, authentication flows or other custom providers. Removing it **also stops webhook notifications**. Integrated applications that rely on those notifications must have a decommissioning/reconciliation plan.

## Versions and build

| Item | Current source |
| --- | --- |
| Maven version | `1.2.0-SNAPSHOT` |
| Maven artifact | `com.leandrosf.keycloak:keycloak-client-role-webhook` |
| Source baseline | Keycloak `26.7.4` |
| Additional CI compile/test target | Keycloak `26.8.2` (branch workflow; not a substitute for end-to-end compatibility tests) |
| Java bytecode target | Java `17` |
| CI runtime | JDK `21` |
| Packaging | JAR |
| Admin UI requirement | Keycloak `declarative-ui` feature |

Build locally:

```bash
mvn clean verify
mvn clean verify -Dkeycloak.version=26.8.2
```

GitHub Actions also runs Maven verification for both Keycloak versions on pushes to `main`/`feature/**` and on pull requests targeting `main`.

Resulting artifact (version in current `pom.xml`):

```text
target/keycloak-client-role-webhook-1.2.0-SNAPSHOT.jar
```

**Passing Maven tests alone is not production approval.** Run integration tests with the actual Keycloak distribution and database configuration in homologation.

## Supported events

| Event | Source | Scope |
| --- | --- | --- |
| `ROLE_REVOKED`, `ROLE_GRANTED` | Keycloak model ProviderEvent | The client that owns the role |
| `USER_REMOVED` | UserPreRemoved ProviderEvent | All clients configured for this event |
| `LOGIN`, `LOGOUT`, `REGISTER` | Native user Event | Client in the event |
| `RESET_PASSWORD`, `UPDATE_EMAIL`, `VERIFY_EMAIL`, `UPDATE_PROFILE` | Native user Event | Client in the event |
| `UPDATE_CREDENTIAL`, `REMOVE_CREDENTIAL`, `DELETE_ACCOUNT` | Native user Event | Client in the event |
| `USER_DISABLED_BY_PERMANENT_LOCKOUT`, `USER_DISABLED_BY_TEMPORARY_LOCKOUT` | Native user Event | Client in the event |

For role events, the listener accepts **client roles**, not realm roles, and checks both the selected event and configured role names. A role filter of `*` monitors all roles of that client. Native user events are sent only when the event provides a usable **realm, client and user ID**. A generic `enabled=false` user update is not itself a supported dedicated event.

## Admin Console configuration

In the realm's **Clients Webhook** page, choose:

1. **Client**: target integrated client (one configuration per client).
2. **Webhook enabled**: on/off.
3. **Monitored events**: one or more events, with no event selected by default.
4. **Monitored roles**: exact client roles or `*` for role grant/revoke.
5. **Webhook URI**: the receiving endpoint; **HTTPS required by default**.
6. **Timeout**: 250–60,000 ms (default 5,000 ms).
7. **Attempts**: 1–5 (default 3).

The optional admin theme `client-webhook-admin` provides the `Clients Webhook > Add` breadcrumb and inherits from `keycloak.v2`.

The listener is registered globally; there is no need to add it manually under Realm Settings > Events > Event listeners.

## Signed webhook HTTP contract (current)

**Breaking change from version 1.1.1:** the request body is no longer the old unsigned event object. It is:

```http
POST /api/keycloak/events HTTP/1.1
Content-Type: application/json
Accept: application/json
X-Webhook-Event-Id: <event-id>
X-Keycloak-Event-Type: ROLE_REVOKED
X-Keycloak-Realm: example-realm

{"webhook_token":"<compact-rs256-jws>"}
```

The JWS header must have `alg=RS256`, `typ=webhook+jwt` and the realm key `kid`. Signed claims include:

| Claim | Meaning |
| --- | --- |
| `iss` | Canonical realm issuer |
| `aud` / `client_id` | Configured recipient client ID |
| `sub` | Keycloak user ID |
| `iat`, `nbf`, `exp` | JWT validity (300 seconds) |
| `jti` | Unique event ID (stable across the current in-memory retry sequence) |
| `token_use` | `client_webhook` |
| `event_type`, `occurred_at`, `source`, `realm` | Signed event metadata |
| `client` | Internal client ID and OIDC client ID |
| `user` | **Only** Keycloak user ID and username |
| `role` | Role ID and name, where applicable |

Email, first name, last name, IP address and OIDC session ID are intentionally excluded from the **signed claims**. The JWT is **signed, not encrypted**; usernames might still contain personal identifiers and must be handled as sensitive data.

### What the receiving system must validate

- Trust a **preconfigured issuer and JWKS**; verify the RS256 signature using the expected `kid`. Never trust an arbitrary JWKS URL provided by the token.
- Enforce exact `iss`, `aud`, `client_id`, `token_use=client_webhook` and `typ=webhook+jwt`; verify `sub == user.id`.
- Validate `iat`, `nbf` and `exp`, including the five-minute maximum lifetime and permitted clock skew.
- Validate the event type, client, role and user **from signed claims**, **not** from untrusted HTTP headers or a separately supplied CPF.
- Deduplicate `jti` durably and make access changes idempotent; reject invalid or unsigned requests.
- Return an HTTP `2xx` response (commonly `204`) after successful or already-completed processing.

Read the complete [JWT security and receiver contract](docs/WEBHOOK_JWT_SECURITY.md) before migrating receivers. **Install and test receiver-side JWT validation before changing the sender.**

## Current post-commit delivery and retries

```text
Keycloak event
   -> filter by configured client/event/role
   -> capture event snapshot and sign RS256 token
   -> transaction commits?
      +-- no  -> discard
      +-- yes -> enqueue HTTP delivery in memory
                  -> 2xx: delivered
                  -> 408 / 429 / 5xx / IO timeout: bounded retries
                  -> other 4xx: stop
```

Keycloak does **not** wait for the remote endpoint while performing the administrative transaction. The HTTP client does **not** follow redirects. There are up to 1–5 attempts according to client configuration, with brief in-memory backoff.

**Limitations:** after-process-crash messages can be lost; the retry queue is not persisted; the single JWT is reused during the existing retry sequence and may expire after five minutes. If signing fails, the sender does **not** fall back to unsigned JSON.

## Webhook destination security

HTTPS is mandatory in normal `start` mode unless the environment explicitly enables the provider's development override. For controlled local tests, Keycloak `start-dev` permits HTTP. The provider option is:

```properties
spi-events-listener--client-webhook-runtime--allow-http=true
```

Do not enable that exception in production. URI validation and TLS do not substitute for firewall rules, egress restrictions or destination allowlists (SSRF prevention). Do not put credentials in webhook URIs. Do not log complete JWTs, user identifiers or secrets unnecessarily.

See [JWT security details](docs/WEBHOOK_JWT_SECURITY.md).

## Install and remove the optional provider

1. Back up configuration and verify receiver compatibility.
2. Copy **one** compatible provider JAR to `<KEYCLOAK_HOME>/providers/`; remove older JAR versions of the same provider.
3. Ensure the `declarative-ui` feature required by the admin page is enabled for the Keycloak version in use.
4. Rebuild the Keycloak distribution (for example, from its installation directory, `bin/kc.bat build` on Windows or `bin/kc.sh build` on Linux, with the required feature settings).
5. Start Keycloak with the site's normal startup procedure; check logs, the admin page, signed delivery and receiver behavior.

**Uninstalling:** disable webhook configurations/stop processing, inspect any undelivered events, remove the provider JAR and its explicit SPI settings, rebuild and restart. Configurations saved as `ComponentModel` may remain in the database until intentionally cleaned up. Do **not** manually edit native Keycloak database rows. Removing the provider stops its notifications and requires planning for applications that depend on those events.

The goal is safe removal of the **custom extension**, not independent execution of the extension without Keycloak.

## Persistent Oracle Outbox — planned, NOT active

Work on durable delivery is isolated on branch [`feature/oracle-webhook-outbox`](https://github.com/leandrosf1/keycloak-client-webhook/tree/feature/oracle-webhook-outbox). The planned table is `KEYCLOAK.KCW_WEBHOOK_OUTBOX` in Oracle 19c. The schema and indexes were created separately in the homologation database; the JAR will **not** create or alter native Keycloak tables or run schema DDL automatically.

The final design aims to:

- Persist monitored administrative events in the **same transaction** as role/user changes and never send HTTP before commit.
- Process events in separate, bounded workers using atomic claims/lease recovery across nodes.
- Keep a stable event ID, but **issue a newly signed JWT on each later delivery attempt**.
- Recover pending deliveries after restarts and apply idempotency at the receiver.
- Default the new feature to **disabled** and never send via both legacy and Outbox paths simultaneously.

These are **design requirements, not completed features**. Listener integration, persistence, worker, Oracle integration tests and a documented policy for Outbox-write failures are required before rollout. The transaction policy must balance guaranteed notification against urgent revocation availability.

See [Outbox implementation contract](docs/OUTBOX_IMPLEMENTATION_CONTRACT.md).

## Database portability

The webhook provider is **not inherently tied to Oracle**. Client/webhook configuration is stored through Keycloak's native `ComponentModel` and the signed-JWT sender does not require a specific database. The **persistent Outbox**, however, is initially being designed and validated for **Oracle Database 19c** with the provider-owned table `KEYCLOAK.KCW_WEBHOOK_OUTBOX`.

The architecture should keep event capture, JWT signing, HTTP delivery and retry orchestration **separate from the database persistence implementation**. This will allow future adapters for other databases officially supported by the relevant Keycloak release (for example, PostgreSQL, MySQL, MariaDB and Microsoft SQL Server), without changing the event/receiver contract.

**This is an extensibility goal, not a claim that the Outbox already works on all databases.** For each additional database, the project will need to provide and validate its schema migration, SQL/JPA compatibility, JSON and timestamp mapping, indexes, atomic event claiming, lock/lease handling, failure recovery and transactional behavior. Tests must demonstrate commit/rollback consistency and concurrent processing on that specific database and Keycloak version.

The first operational target remains Oracle 19c. The initial JAR must not create or alter Keycloak's native schema automatically, and a database adapter may not compromise the provider's ability to be disabled or removed cleanly. Database support should be explicitly documented only after successful integration testing.

## Tests, releases and mandatory pre-merge documentation

Every pull request intended for `main` must satisfy **all** of the following:

- [ ] README updated in the **feature branch** to describe the **actual code**, version, compatibility, configuration, receiver protocol, limitations, migration and rollback/uninstall behavior; planned features must be clearly identified as such.
- [ ] Security and other detailed documentation updated when contracts or settings change.
- [ ] Relevant positive, negative, regression and invariant **automated tests** added/updated, with results reported.
- [ ] GitHub Actions Maven verification passes for both supported Keycloak versions; any integration-test gaps are made explicit.
- [ ] For changes involving durable delivery, actual Oracle 19c/Keycloak homologation tests cover commit/rollback, database failures, concurrency, recovery, retries and token validity.
- [ ] No credentials, private keys, internal sensitive endpoints or production data committed.
- [ ] A pull request is reviewed, and **merge to `main` occurs only after explicit approval**.

Passing checks is **not** permission to deploy. Production activation requires a separate decision, receiver validation and a rollback plan.

## Repository layout

```text
src/main/java/com/leandrosf/keycloak/clientwebhook/
  admin/     ClientWebhookAdminPage
  config/    Per-client configuration, supported event types, transport policy
  model/     ClientWebhookPayload
  runtime/   Event listener, in-memory dispatcher, RS256 signer/claims
             (Outbox support classes under active development on feature branch)
src/main/resources/META-INF/services/  Keycloak SPI registrations
src/main/resources/theme/              Optional admin breadcrumb theme
src/test/java/                         Unit tests
docs/                                  JWT contract and Outbox design
.github/workflows/maven.yml            CI verification
```

## Historical versions

- **1.1.1** — Configurable events and client-role filters, Admin Console page/theme, post-commit HTTP JSON delivery with in-memory retries. **Unsigned JSON contract; legacy only.**
- **1.2.0-SNAPSHOT (current source on `main`)** — RS256 signed JWT with reduced user claims, fail-closed signature handling, HTTPS-by-default delivery, dedicated development-only HTTP option, security contract and automated tests. **Breaking receiver protocol change.**
- **Oracle Outbox** — Under development; **not yet released, merged or operational**.

## Contributing and licensing

When reporting bugs, provide Keycloak, Java and provider versions, the selected event and **sanitized** relevant logs. Issues and pull requests are welcome.

No `LICENSE` file is present in this repository. Adding a license requires a separate decision by the project owner.
