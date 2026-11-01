package com.leandrosf.keycloak.clientwebhook.config;

public final class ClientWebhookConfigKeys {

    private ClientWebhookConfigKeys() {
    }

    public static final String PROVIDER_ID = "Clients Webhook";

    public static final String CLIENT_ID = "clientId";
    public static final String ENABLED = "enabled";
    public static final String EVENTS = "events";
    public static final String ROLES = "roles";
    public static final String URL = "url";
    public static final String TIMEOUT_MS = "timeoutMs";
    public static final String MAX_ATTEMPTS = "maxAttempts";

    public static final int DEFAULT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    public static final int MIN_TIMEOUT_MS = 250;
    public static final int MAX_TIMEOUT_MS = 60_000;
    public static final int MIN_ATTEMPTS = 1;
    public static final int MAX_ALLOWED_ATTEMPTS = 5;
}
