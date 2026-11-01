# Keycloak Client Webhook

Extension for **Keycloak 26.7.4** that allows you to configure **webhooks per client and per event** directly from the Keycloak Admin Console.

The goal of this project is to allow systems integrated with Keycloak to be automatically notified when relevant user-related events occur, without requiring additional logic in a portal application or an intermediate integration service.

Webhook configuration is managed per client from the **Clients Webhook** page, and delivery is performed only **after the Keycloak transaction commits successfully**, preventing external calls from being triggered when the main operation is rolled back.

> **Current version:** `1.1.1`  
> **Target Keycloak version:** `26.7.4`  
> **Java compilation target:** `17+`  
> **Build tool:** Maven

---

## Overview

In many environments, each application integrated with SSO has its own client in Keycloak. In addition to authentication, these applications may need to be notified when something relevant happens to a user, for example:

- a client role is removed from a user;
- a client role is assigned to a user;
- a user logs in;
- a user logs out;
- a user is removed from the realm;
- a user changes credentials or profile information;
- a user is temporarily or permanently locked out.

**Keycloak Client Webhook** adds a **Clients Webhook** page to the Admin Console, where an independent webhook configuration can be created for each client.

Each configuration defines:

- the client representing the integrated application;
- whether the webhook is enabled;
- which events should be monitored;
- which client roles should be monitored for role grant/revocation events;
- the HTTP/HTTPS endpoint that will receive the webhook;
- request timeout;
- maximum number of delivery attempts.

---

## Features

- Webhook configuration **per client**.
- Selection of **multiple events** per client.
- Client role filtering for `ROLE_GRANTED` and `ROLE_REVOKED`.
- Wildcard `*` support to monitor all roles of a client.
- JSON payload over HTTP.
- Delivery only **after commit** of the Keycloak transaction.
- Automatic webhook discard when the transaction is rolled back.
- Retry support for temporary failures.
- Unique `eventId` for each webhook, enabling idempotency on the receiving system.
- Administrative page integrated into the Keycloak Admin Console.
- Optional admin theme to display the breadcrumb `Clients Webhook > Add`.
- No user password, access token, or refresh token is included in the payload.

---

## Architecture

General flow:

```text
User / Administrator
        |
        | event in Keycloak
        v
+-----------------------------+
|          Keycloak           |
|                             |
| User Event / Provider Event |
+--------------+--------------+
               |
               v
+-----------------------------+
| Keycloak Client Webhook     |
|                             |
| 1. identifies the client    |
| 2. loads its configuration  |
| 3. validates event/role     |
| 4. waits for commit         |
+--------------+--------------+
               |
               | HTTP POST
               v
+-----------------------------+
| Integrated application      |
| configured endpoint         |
+-----------------------------+
```

Each webhook configuration is persisted by Keycloak through `ComponentModel` and is associated with the realm. The project does not access database tables directly.

---

## Supported Events

Version `1.1.1` supports the following events:

| Event | Source | Scope | Description |
|---|---|---|---|
| `ROLE_REVOKED` | Provider Event | Client | A client role was removed from a user |
| `ROLE_GRANTED` | Provider Event | Client | A client role was assigned to a user |
| `LOGIN` | User Event | Client | User login for a client |
| `LOGOUT` | User Event | Client | Logout associated with a client |
| `REGISTER` | User Event | Client | User registration when the event contains client and user information |
| `RESET_PASSWORD` | User Event | Client | Password reset |
| `UPDATE_EMAIL` | User Event | Client | Email update |
| `VERIFY_EMAIL` | User Event | Client | Email verification |
| `UPDATE_PROFILE` | User Event | Client | User profile update |
| `UPDATE_CREDENTIAL` | User Event | Client | Credential update |
| `REMOVE_CREDENTIAL` | User Event | Client | Credential removal |
| `DELETE_ACCOUNT` | User Event | Client | Self-service account deletion when emitted by the Keycloak flow |
| `USER_DISABLED_BY_PERMANENT_LOCKOUT` | User Event | Client | Native permanent lockout event from Keycloak |
| `USER_DISABLED_BY_TEMPORARY_LOCKOUT` | User Event | Client | Native temporary lockout event from Keycloak |
| `USER_REMOVED` | Provider Event | Realm / broadcast | Sent to every configured client subscribed to this event |

### ROLE_REVOKED and ROLE_GRANTED

These events are handled through Keycloak's internal model events.

When a client role is removed from or assigned to a user, the provider:

1. identifies the role;
2. checks whether it is a **client role**;
3. identifies the client that owns the role;
4. looks up the webhook configuration for that client;
5. checks whether the webhook is enabled;
6. checks whether the event is selected;
7. checks whether the role is included in the monitored roles list;
8. schedules delivery to occur only after the transaction commits successfully.

Realm roles are not handled by this flow in the current version.

### User events

Events such as `LOGIN`, `LOGOUT`, `RESET_PASSWORD`, and `UPDATE_PROFILE` are received through `EventListenerProvider`.

A webhook is sent only when the Keycloak event provides:

- realm;
- client;
- user.

As a result, a login to client `application-a` does not trigger the webhook configured for `application-b`.

### USER_REMOVED

`USER_REMOVED` is different from the other events because deleting a user is a realm-level operation and does not naturally belong to a single client.

When a user is removed, the provider captures a snapshot of the user's data before deletion and finds all webhook configurations in the realm that subscribe to `USER_REMOVED`.

Example:

```text
User removed
       |
       +--> Client A subscribes to USER_REMOVED -> send
       +--> Client B does not subscribe          -> ignore
       +--> Client C subscribes to USER_REMOVED -> send
```

Delivery still occurs only after the user deletion transaction commits successfully.

---

## Clients Webhook Page

After installation, the Admin Console displays a new menu option:

```text
Clients Webhook
```

Each item represents a webhook configuration for one client.

### Fields

#### Client

Selects the client representing the integrated application.

Only one webhook configuration can exist for each client.

#### Webhook enabled

Enables or disables delivery without deleting the configuration.

#### Monitored events

List of events that can trigger the webhook.

At least one event must be selected.

No event is preselected by default.

#### Monitored roles

Used only when `ROLE_REVOKED` or `ROLE_GRANTED` is selected.

You can specify individual roles:

```text
USER
MANAGER
ADMINISTRATOR
```

or use:

```text
*
```

to monitor all client roles for that client.

When `*` is used, it must be the only value in the field.

The configuration validates that the specified roles actually exist in the selected client.

#### Webhook URI

HTTP or HTTPS endpoint that will receive the JSON payload.

Example:

```text
https://application.example.org/api/keycloak/events
```

The URI must contain a valid host and must not include embedded username/password credentials.

#### Timeout

Maximum duration of each HTTP attempt.

Accepted range:

```text
250 ms to 60000 ms
```

Default value:

```text
5000 ms
```

#### Attempts

Maximum number of delivery attempts.

Accepted range:

```text
1 to 5
```

Default value:

```text
3
```

---

## Post-Commit Delivery

The project uses the Keycloak transaction manager to register webhook delivery through `enlistAfterCompletion`.

This means the HTTP request is not part of the main Keycloak transaction.

```text
Keycloak operation
        |
        v
transaction in progress
        |
   +----+----+
   |         |
commit    rollback
   |         |
   v         v
send      discard
webhook   webhook
```

This behavior is especially important for events such as role revocation and user deletion.

An unavailable remote endpoint should also not prevent the main Keycloak operation from completing successfully.

---

## Retry Behavior

Delivery is retried when a failure is considered temporary:

- connection error;
- timeout;
- HTTP `408`;
- HTTP `429`;
- HTTP `5xx`.

Any HTTP status between `200` and `299` is considered successful.

`4xx` responses other than `408` and `429` are not retried.

The delay between retries uses a simple backoff strategy capped at 2 seconds.

> Retry state is stored only in memory. The current version does not provide a persistent outbox or durable queue.

---

## Payload

All events use a common payload structure.

### Example: ROLE_REVOKED

```json
{
  "eventId": "b2df4c59-1ab6-4c23-bb1e-b274efb81e92",
  "eventType": "ROLE_REVOKED",
  "occurredAt": "2026-10-02T11:00:00Z",
  "source": "PROVIDER_EVENT",
  "realm": "my-realm",
  "client": {
    "id": "internal-client-uuid",
    "clientId": "integrated-application"
  },
  "user": {
    "id": "user-uuid",
    "username": "user123",
    "email": "user@example.org",
    "firstName": "John",
    "lastName": "Doe"
  },
  "role": {
    "id": "role-uuid",
    "name": "USER"
  },
  "context": null
}
```

### Example: LOGIN

```json
{
  "eventId": "0f798892-c66d-41cf-9ac9-26ae67e06fd0",
  "eventType": "LOGIN",
  "occurredAt": "2026-10-02T11:01:00Z",
  "source": "USER_EVENT",
  "realm": "my-realm",
  "client": {
    "id": "internal-client-uuid",
    "clientId": "integrated-application"
  },
  "user": {
    "id": "user-uuid",
    "username": "user123",
    "email": "user@example.org",
    "firstName": "John",
    "lastName": "Doe"
  },
  "role": null,
  "context": {
    "ipAddress": "192.0.2.10",
    "sessionId": "session-id"
  }
}
```

### Fields

| Field | Description |
|---|---|
| `eventId` | Unique webhook identifier |
| `eventType` | Event type |
| `occurredAt` | Event date/time in UTC |
| `source` | `PROVIDER_EVENT` or `USER_EVENT` |
| `realm` | Realm name |
| `client` | Client identification |
| `user` | Snapshot of available user data |
| `role` | Role information when applicable |
| `context` | Additional information such as IP address and session ID when available |

The project does not send passwords, access tokens, refresh tokens, or user credentials.

---

## HTTP Headers

Each request includes:

```text
Content-Type: application/json
Accept: application/json
X-Webhook-Event-Id: <eventId>
X-Keycloak-Event-Type: <eventType>
X-Keycloak-Realm: <realm>
User-Agent: Keycloak-Client-Webhook/<version>
```

The receiving system should preferably use `eventId` as an idempotency key to prevent duplicate side effects when retries occur.

---

## Requirements

This version was developed for:

- Keycloak `26.7.4`;
- Maven `3.8+`;
- Java `17+` for compilation;
- `declarative-ui` feature enabled.

The project uses internal Keycloak SPIs, including the administrative UI SPI. Because of that, future Keycloak versions may require code changes and should be tested before a production upgrade.

---

## Project Structure

```text
src/main/java/com/leandrosf/keycloak/clientwebhook
|
+-- admin
|   +-- ClientWebhookAdminPage.java
|
+-- config
|   +-- ClientWebhookConfig.java
|   +-- ClientWebhookConfigKeys.java
|   +-- ClientWebhookConfigRepository.java
|   +-- WebhookEventType.java
|
+-- model
|   +-- ClientWebhookPayload.java
|
+-- runtime
    +-- ClientWebhookDispatcher.java
    +-- ClientWebhookEventListenerFactory.java
```

Resources:

```text
src/main/resources
|
+-- META-INF
|   +-- services
|   |   +-- org.keycloak.events.EventListenerProviderFactory
|   |   +-- org.keycloak.services.ui.extend.UiPageProviderFactory
|   +-- keycloak-themes.json
|
+-- theme/client-webhook-admin/admin
    +-- theme.properties
    +-- messages/
```

---

## Build

From the project root directory:

```bash
mvn clean package
```

On Windows, if you need to use a specific Java installation only for the current terminal session:

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

java -version
mvn -version
mvn clean package
```

The artifact will be generated at:

```text
target/keycloak-client-role-webhook-1.1.1.jar
```

---

## Installing on Keycloak

### 1. Copy the JAR

Copy the generated JAR to:

```text
<KEYCLOAK_HOME>/providers/
```

Example on Windows:

```text
C:\Keycloak\keycloak-26.7.4\providers\keycloak-client-role-webhook-1.1.1.jar
```

Do not keep older versions of the same provider in the `providers` directory.

### 2. Rebuild Keycloak

```bat
cd C:\Keycloak\keycloak-26.7.4\bin
kc.bat build --feature-declarative-ui=enabled
```

### 3. Start Keycloak

Use the normal startup procedure for your environment, for example:

```bat
kc.bat start --optimized
```

### 4. Verify provider loading

The startup log should contain references to the extension providers, including the administrative page and the event listener.

---

## Admin Theme Configuration

Keycloak 26.7.4 uses the translation key `page` in declarative-page breadcrumbs.

To display:

```text
Clients Webhook > Add
```

instead of:

```text
page > Add
```

the project includes the following admin theme:

```text
client-webhook-admin
```

To enable it:

1. Open **Realm settings**.
2. Open **Themes**.
3. Under **Admin console theme**, select `client-webhook-admin`.
4. Save the configuration.
5. Reload the Admin Console.

The theme inherits from `keycloak.v2` and overrides only the message required for the breadcrumb.

---

## Creating a Webhook Configuration

After installation:

1. Open the desired realm.
2. In the left navigation menu, click **Clients Webhook**.
3. Click **Add**.
4. Select the client.
5. Enable **Webhook enabled**.
6. Select one or more events under **Monitored events**.
7. If `ROLE_REVOKED` or `ROLE_GRANTED` is selected, specify the desired roles or `*`.
8. Enter the **Webhook URI**.
9. Adjust timeout and attempts if necessary.
10. Save.

The configuration takes effect immediately for events processed by the provider.

There is no need to manually add this listener under **Realm Settings > Events > Event listeners**, because the factory is registered globally.

---

## Example Configuration

```text
Client: payroll-system
Webhook enabled: true
Events: ROLE_REVOKED, ROLE_GRANTED, LOGIN
Roles: USER, MANAGER
URI: https://application.example.org/api/keycloak/events
Timeout: 5000
Attempts: 3
```

With this configuration:

- removing role `USER` from a user triggers `ROLE_REVOKED`;
- assigning role `MANAGER` triggers `ROLE_GRANTED`;
- assigning or removing another role that is not configured does not trigger a webhook;
- login to client `payroll-system` triggers `LOGIN`;
- login to another client does not use this configuration.

---

## Receiving Endpoint Requirements

The receiving endpoint should:

- accept `POST` requests;
- accept `application/json`;
- return an HTTP `2xx` status when the event is processed successfully;
- be idempotent, preferably by using `eventId`;
- respond within the configured timeout;
- handle retries without duplicating business effects.

Conceptual example:

```http
POST /api/keycloak/events
Content-Type: application/json
X-Webhook-Event-Id: b2df4c59-1ab6-4c23-bb1e-b274efb81e92
X-Keycloak-Event-Type: ROLE_REVOKED
```

Successful response:

```http
204 No Content
```

---

## Security

The current version does not implement an HMAC secret or dedicated token per client.

For production environments, protect the receiving endpoint using one or more of the following controls:

- private/internal network;
- firewall rules;
- reverse proxy;
- source allowlisting;
- TLS;
- mTLS;
- additional authentication implemented by the receiving endpoint.

Never embed a username and password directly in the webhook URI.

Also, do not publish the following in a public GitHub repository:

- passwords;
- client secrets;
- tokens;
- private certificates;
- keystores;
- environment configuration files containing credentials;
- sensitive internal URLs.

---

## Idempotency

Retries can result in more than one HTTP request for the same event.

For that reason, the receiving system should store or check:

```text
eventId
```

before executing an irreversible business effect.

Example:

```text
receive eventId ABC
    |
    +-- already processed -> return 2xx without repeating the operation
    |
    +-- new -> process, store ABC, return 2xx
```

---

## Current Limitations

- Officially tested/declared compatibility is limited to Keycloak `26.7.4`.
- The project uses internal Keycloak SPIs that may change between versions.
- Realm roles are not handled by `ROLE_GRANTED` and `ROLE_REVOKED`.
- Roles inherited through groups are not automatically converted into direct user role events.
- A simple administrative user disable operation (`enabled=false`) does not have a dedicated event implementation in this version.
- `USER_DISABLED_BY_PERMANENT_LOCKOUT` and `USER_DISABLED_BY_TEMPORARY_LOCKOUT` represent Keycloak's native lockout events.
- Retry state is kept in memory only.
- There is no persistent outbox or durable queue.
- There is no per-client HMAC signature in this version.
- HTTP redirects are not followed by the HTTP client.

For scenarios that require strong delivery guarantees, consider extending the project with a persistent outbox or messaging integration.

---

## Version 1.1.1

Main features and fixes:

- configurable events per client;
- `ROLE_REVOKED` is no longer mandatory or preselected;
- explicit selection of at least one event;
- fix for `MULTIVALUED_LIST_TYPE` form handling in the Keycloak 26.7.4 Admin Console;
- **Clients Webhook** administrative page;
- webhook URI rendered as a text field;
- optional `Clients Webhook > Add` breadcrumb through the admin theme;
- Java namespace `com.leandrosf.keycloak.clientwebhook`;
- post-commit webhook delivery;
- configurable retry behavior.

---

## Development

Java namespace:

```text
com.leandrosf.keycloak.clientwebhook
```

Maven coordinates:

```xml
<groupId>com.leandrosf.keycloak</groupId>
<artifactId>keycloak-client-role-webhook</artifactId>
<version>1.1.1</version>
```

---

## Contributing

Issues and pull requests are welcome for bug fixes, additional events, HMAC authentication, persistent outbox support, metrics, and compatibility improvements for other Keycloak versions.

When reporting an issue, please include at least:

- Keycloak version;
- Java version;
- plugin version;
- configured event;
- relevant log excerpt with sensitive information removed.

---

## License

Choose a license before publishing the project as open source. Common options for Java/Keycloak extensions include **Apache License 2.0** and **MIT License**.

Without a `LICENSE` file, the source code remains protected by default copyright rules and third parties do not automatically receive broad permission to reuse it.
