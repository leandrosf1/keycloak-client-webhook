package com.leandrosf.keycloak.clientwebhook.config;

import org.keycloak.events.EventType;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Eventos que podem ser selecionados em cada configuracao de webhook.
 *
 * ROLE_GRANTED, ROLE_REVOKED e USER_REMOVED sao ProviderEvents internos.
 * Os demais sao EventType nativos do Keycloak e sao tratados pelo
 * EventListenerProvider.
 */
public enum WebhookEventType {

    ROLE_REVOKED,
    ROLE_GRANTED,

    LOGIN,
    LOGOUT,
    REGISTER,
    RESET_PASSWORD,
    UPDATE_EMAIL,
    VERIFY_EMAIL,
    UPDATE_PROFILE,
    UPDATE_CREDENTIAL,
    REMOVE_CREDENTIAL,
    DELETE_ACCOUNT,
    USER_DISABLED_BY_PERMANENT_LOCKOUT,
    USER_DISABLED_BY_TEMPORARY_LOCKOUT,

    USER_REMOVED;

    public boolean isRoleEvent() {
        return this == ROLE_REVOKED || this == ROLE_GRANTED;
    }

    public boolean isBroadcastEvent() {
        return this == USER_REMOVED;
    }

    public boolean isKeycloakUserEvent() {
        return !isRoleEvent() && !isBroadcastEvent();
    }

    public static Optional<WebhookEventType> fromKeycloakEventType(EventType type) {
        if (type == null) {
            return Optional.empty();
        }

        try {
            WebhookEventType value = WebhookEventType.valueOf(type.name());
            return value.isKeycloakUserEvent() ? Optional.of(value) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static List<String> supportedCodes() {
        return Arrays.stream(values())
                .map(Enum::name)
                .toList();
    }
}
