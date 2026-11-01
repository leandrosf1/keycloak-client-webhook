package com.leandrosf.keycloak.clientwebhook.config;

import org.keycloak.models.RealmModel;
import org.keycloak.services.ui.extend.UiPageProvider;

import java.util.List;
import java.util.Optional;

public final class ClientWebhookConfigRepository {

    private ClientWebhookConfigRepository() {
    }

    public static Optional<ClientWebhookConfig> findByClientId(RealmModel realm, String clientId) {
        if (realm == null || clientId == null) {
            return Optional.empty();
        }

        return components(realm)
                .stream()
                .filter(config -> clientId.equals(config.clientId()))
                .findFirst();
    }

    public static List<ClientWebhookConfig> findAll(RealmModel realm) {
        if (realm == null) {
            return List.of();
        }
        return components(realm);
    }

    private static List<ClientWebhookConfig> components(RealmModel realm) {
        return realm.getComponentsStream(realm.getId(), UiPageProvider.class.getName())
                .filter(component -> ClientWebhookConfigKeys.PROVIDER_ID.equals(component.getProviderId()))
                .map(ClientWebhookConfig::from)
                .toList();
    }
}
