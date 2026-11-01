package com.leandrosf.keycloak.clientwebhook.model;

public record ClientWebhookPayload(
        String eventId,
        String eventType,
        String occurredAt,
        String source,
        String realm,
        ClientInfo client,
        UserInfo user,
        RoleInfo role,
        ContextInfo context) {

    public static final String SOURCE_PROVIDER_EVENT = "PROVIDER_EVENT";
    public static final String SOURCE_USER_EVENT = "USER_EVENT";

    public record ClientInfo(
            String id,
            String clientId) {
    }

    public record UserInfo(
            String id,
            String username,
            String email,
            String firstName,
            String lastName) {
    }

    public record RoleInfo(
            String id,
            String name) {
    }

    public record ContextInfo(
            String ipAddress,
            String sessionId) {
    }
}
