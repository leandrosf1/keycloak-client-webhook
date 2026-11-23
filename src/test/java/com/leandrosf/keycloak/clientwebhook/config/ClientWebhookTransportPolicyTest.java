package com.leandrosf.keycloak.clientwebhook.config;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class ClientWebhookTransportPolicyTest {

    @Test
    void defaultProductionPolicyRejectsHttpButAcceptsHttps() {
        assertFalse(ClientWebhookTransportPolicy.isHttpAllowed(false, false));
        assertTrue(ClientWebhookTransportPolicy.isAllowedEndpoint(
                URI.create("https://app.example.org/hook"), false));
        assertFalse(ClientWebhookTransportPolicy.isAllowedEndpoint(
                URI.create("http://app.example.org/hook"), false));
    }

    @Test
    void startDevOrExplicitOptInEnablesHttp() {
        assertTrue(ClientWebhookTransportPolicy.isHttpAllowed(true, false));
        assertTrue(ClientWebhookTransportPolicy.isHttpAllowed(false, true));
        assertTrue(ClientWebhookTransportPolicy.isHttpAllowed(true, true));
        assertTrue(ClientWebhookTransportPolicy.isAllowedEndpoint(
                URI.create("http://192.0.2.10:8080/eSEGTRAB/webhook"), true));
    }

    @Test
    void unsafeOrMalformedTargetsAreAlwaysRejected() {
        assertFalse(ClientWebhookTransportPolicy.isAllowedEndpoint(null, true));
        assertFalse(ClientWebhookTransportPolicy.isAllowedEndpoint(
                URI.create("ftp://app.example.org/hook"), true));
        assertFalse(ClientWebhookTransportPolicy.isAllowedEndpoint(
                URI.create("https://user:pass@app.example.org/hook"), true));
        assertFalse(ClientWebhookTransportPolicy.isAllowedEndpoint(
                URI.create("http://app.example.org/hook#frag"), true));
        assertFalse(ClientWebhookTransportPolicy.isAllowedEndpoint(
                URI.create("http:///relative-path"), true));
        assertFalse(ClientWebhookTransportPolicy.isAllowedEndpoint(
                URI.create("file:///etc/passwd"), true));
    }
}
