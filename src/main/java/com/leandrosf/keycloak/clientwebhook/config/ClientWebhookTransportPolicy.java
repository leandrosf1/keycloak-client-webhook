package com.leandrosf.keycloak.clientwebhook.config;

import org.keycloak.Config;
import org.keycloak.common.util.Environment;

import java.net.URI;

/**
 * Controls outgoing webhook transport. HTTP is insecure and is therefore
 * enabled only for an actual Keycloak dev profile or an explicit SPI opt-in.
 * This policy is shared by the Admin Console validator and the runtime sender.
 */
public final class ClientWebhookTransportPolicy {

    public static final String SPI_NAME = "eventsListener";
    public static final String PROVIDER_ID = "client-webhook-runtime";
    public static final String OPTION = "allow-http";

    private ClientWebhookTransportPolicy() {
    }

    public static boolean isHttpAllowed() {
        boolean explicitlyAllowed = Boolean.TRUE.equals(
                Config.scope(SPI_NAME, PROVIDER_ID).getBoolean(OPTION, Boolean.FALSE));
        return isHttpAllowed(Environment.isDevMode(), explicitlyAllowed);
    }

    public static boolean isHttpAllowed(boolean developmentMode, boolean explicitlyAllowed) {
        return developmentMode || explicitlyAllowed;
    }

    /**
     * Return true only for an acceptable configured URI, including host/userinfo
     * and fragment checks. This does not replace destination allowlists or egress rules.
     */
    public static boolean isAllowedEndpoint(URI uri) {
        return isAllowedEndpoint(uri, isHttpAllowed());
    }

    public static boolean isAllowedEndpoint(URI uri, boolean httpAllowed) {
        if (uri == null || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        return httpAllowed && "http".equalsIgnoreCase(uri.getScheme());
    }
}
