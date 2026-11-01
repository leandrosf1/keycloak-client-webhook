package com.leandrosf.keycloak.clientwebhook.config;

import org.keycloak.component.ComponentModel;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ClientWebhookConfig {

    private final String componentId;
    private final String clientId;
    private final boolean enabled;
    private final URI url;
    private final Set<WebhookEventType> events;
    private final Set<String> roles;
    private final int timeoutMs;
    private final int maxAttempts;

    public ClientWebhookConfig(
            String componentId,
            String clientId,
            boolean enabled,
            URI url,
            Set<WebhookEventType> events,
            Set<String> roles,
            int timeoutMs,
            int maxAttempts) {
        this.componentId = componentId;
        this.clientId = clientId;
        this.enabled = enabled;
        this.url = url;
        this.events = Collections.unmodifiableSet(new LinkedHashSet<>(events));
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        this.timeoutMs = timeoutMs;
        this.maxAttempts = maxAttempts;
    }

    public static ClientWebhookConfig from(ComponentModel model) {
        Objects.requireNonNull(model, "model");

        String clientId = trimToNull(model.get(ClientWebhookConfigKeys.CLIENT_ID));
        String rawUrl = trimToNull(model.get(ClientWebhookConfigKeys.URL));

        Set<WebhookEventType> events = readEvents(model);
        Set<String> roles = readStrings(model, ClientWebhookConfigKeys.ROLES);

        URI uri = rawUrl == null ? null : URI.create(rawUrl);

        return new ClientWebhookConfig(
                model.getId(),
                clientId,
                model.get(ClientWebhookConfigKeys.ENABLED, false),
                uri,
                events,
                roles,
                model.get(ClientWebhookConfigKeys.TIMEOUT_MS, ClientWebhookConfigKeys.DEFAULT_TIMEOUT_MS),
                model.get(ClientWebhookConfigKeys.MAX_ATTEMPTS, ClientWebhookConfigKeys.DEFAULT_MAX_ATTEMPTS));
    }

    private static Set<WebhookEventType> readEvents(ComponentModel model) {
        List<String> rawEvents = model.getConfig().get(ClientWebhookConfigKeys.EVENTS);
        Set<WebhookEventType> events = new LinkedHashSet<>();

        if (rawEvents != null) {
            rawEvents.stream()
                    .map(ClientWebhookConfig::trimToNull)
                    .filter(Objects::nonNull)
                    .forEach(value -> {
                        try {
                            events.add(WebhookEventType.valueOf(value));
                        } catch (IllegalArgumentException ignored) {
                            // Configuracao antiga/desconhecida: simplesmente ignora o valor invalido.
                        }
                    });
        }

        return events;
    }

    private static Set<String> readStrings(ComponentModel model, String key) {
        List<String> values = model.getConfig().get(key);
        Set<String> result = new LinkedHashSet<>();

        if (values != null) {
            values.stream()
                    .map(ClientWebhookConfig::trimToNull)
                    .filter(Objects::nonNull)
                    .forEach(result::add);
        }

        return result;
    }

    public boolean monitorsEvent(WebhookEventType eventType) {
        return events.contains(eventType);
    }

    public boolean monitorsRole(String roleName) {
        return roles.contains("*") || roles.contains(roleName);
    }

    public String componentId() {
        return componentId;
    }

    public String clientId() {
        return clientId;
    }

    public boolean enabled() {
        return enabled;
    }

    public URI url() {
        return url;
    }

    public Set<WebhookEventType> events() {
        return events;
    }

    public Set<String> roles() {
        return roles;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
