# Signed Client Webhook — JWT contract (draft, 1.2.0-SNAPSHOT)

> **Breaking change.** This is a development proposal for Keycloak 26.7.4. Do not deploy this branch until every configured receiver is prepared to reject unsigned JSON and the full Maven build/integration tests have passed.

## Sender behavior

The provider captures client/user/role event data inside the Keycloak request. It signs the event once using the **active RS256 private key of the emitting realm**, and queues delivery **only after transaction commit**. A rollback discards the message. It must **never** fall back to unsigned JSON if signing fails.

The HTTP request is HTTPS-only and does not follow redirects:

```http
POST /api/keycloak/events HTTP/1.1
Content-Type: application/json
X-Webhook-Event-Id: 4e643ece-15cb-4b35-b60a-a495966abf8c

{"webhook_token":"<compact-JWS>"}
```

The outer event ID and any other informational headers are **untrusted metadata**. Only use claims **inside the verified JWT** to authorize changes.

The JWT header must contain:
- `alg: RS256`
- `typ: webhook+jwt` (exclusive type, not `JWT`, `logout+jwt` or a normal access token)
- `kid` identifying the realm signing key.

The signed claims include:

```json
{
  "iss": "https://sso.example.org/realms/my-realm",
  "aud": "integrated-client",
  "sub": "keycloak-user-uuid",
  "iat": 1800000000,
  "nbf": 1800000000,
  "exp": 1800000300,
  "jti": "event-uuid",
  "token_use": "client_webhook",
  "event_type": "ROLE_REVOKED",
  "realm": "my-realm",
  "client_id": "integrated-client",
  "occurred_at": "2026-11-12T12:00:00Z",
  "source": "PROVIDER_EVENT",
  "client": {"id":"keycloak-client-uuid", "clientId":"integrated-client"},
  "user": {"id":"keycloak-user-uuid", "username":"12345678901"},
  "role": {"id":"role-uuid", "name":"my-client-role"}
}
```

Optional user profile values such as email/name may also be present. The OIDC session ID and request IP are deliberately omitted. The **JWT is signed, not encrypted**; do not put credentials or other secrets into claims. `sub` is the Keycloak user ID and `user.username` is the username snapshot; if the deployment uses CPF as username, the receiver can use it **only after signature validation**.

An event is assigned one `jti` per **destination**. The provider creates and signs a single JWT before queueing it; all retries within that delivery reuse the same signed token and `jti`. Tokens have a 300-second lifetime. If delivery is delayed beyond expiry, the receiver must reject it; this version does not persist or renew deliveries.

## Mandatory receiver checks

1. **Allow HTTPS POST only**. Set maximum request and token sizes. Do not log the full JWT.
2. Parse the compact JWT defensively. Require exactly three segments and enforce `alg=RS256` and `typ=webhook+jwt`. Reject unsupported algorithms, `none`, missing `kid`, or missing claims.
3. Verify the JWS **signature** with the **preconfigured issuer's** JWKS, e.g. `https://sso.example.org/realms/my-realm/protocol/openid-connect/certs`. Cache keys and refresh carefully on key rotation. **Never follow `jku`/`x5u`/untrusted JWKS URLs supplied in the JWT**.
4. Require exact `iss` (canonical realm issuer), exact `aud` and exact `client_id` equal to the receiver's own **client ID**, and exact `token_use=client_webhook`.
5. Require `iat`, `nbf` and `exp` numeric, with a short bounded clock skew (e.g. 60 seconds); reject expired, future-issued or implausibly long-lived tokens. The expected maximum `exp-iat` is 300 seconds.
6. Require a nonempty `jti`, `sub`, `event_type`, `realm`, `user.id`, and `user.username`. Require `sub == user.id`; validate event-specific claims (e.g. `role.name` for `ROLE_REVOKED`).
7. Only process explicitly permitted events and roles. Decide whether a removed role **actually revokes access** (remaining client roles/composite roles can still authorize it); optionally query the current identity state to prevent old events being applied after a subsequent grant.
8. Atomically claim `(iss, aud, jti)` and perform the local authorization change in the same durable transaction (unique constraint). Handle concurrent duplicate requests without repeating effects. Retain idempotency records past the entire replay/delivery window.
9. Return `204` for both first-time successful processing and already-processed deliveries; reject invalid signatures or claims with `401/403`. A response `2xx` is treated as final success by the sender.

Never accept a separately supplied CPF, username, user ID, role name or event type as authority if it is absent from or conflicts with the signed JWT. **Remove the old unsigned endpoint behavior** when migrating.

## Deployment and caveats

- Use a **canonical frontend hostname/issuer** for the realm, and confirm its RS256 key is active and exposed in JWKS. No webhook-specific HMAC secret is required.
- Install one provider JAR at a time, enable `declarative-ui`, and configure only HTTPS destinations in **Clients Webhook**.
- Roll out receiver JWT validation **before** switching the sender; otherwise existing receivers will fail to recognize the new body.
- The current queue/retries are **in memory**, not a transactional outbox. A Keycloak crash after commit can lose an event. State reconciliation/transactional outbox is a future improvement.
- The 5-minute JWT lifetime is bounded; a 5-attempt configuration at 60-second timeout can outlive the token. Review receiver behavior and retry limits for each deployment.
- Destination validation currently enforces HTTPS, a valid host, no user info and no URL fragment. **This is not full SSRF prevention**: production should impose outbound network rules and allowed destination domains/addresses.
- Signed tokens authenticate the event but do not guarantee correct event ordering. Receivers must handle stale revocations, retries, and newly granted access.
- Current Java files are built against **Keycloak 26.7.4** (including internal SPI). A full Keycloak integration test with the target distribution is required before release.

## Verification

Build and run the unit tests with Maven and JDK 21:

```bash
mvn clean verify
```

For a receiver, verify at minimum: valid RS256; tampered token; unsigned/other JWT kind; wrong audience; wrong issuer; wrong client ID; expired/future token; missing role; duplicate/concurrent `jti`; stale revoke after a later grant; invalid TLS certificate; and HTTP retries.

No receiver-side authentication or idempotency store is deployed by this Java provider; each integrated application must implement the receiver contract.
