package com.leandrosf.keycloak.clientwebhook.admin;

import com.leandrosf.keycloak.clientwebhook.config.ClientWebhookConfigKeys;
import com.leandrosf.keycloak.clientwebhook.config.WebhookEventType;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.services.ui.extend.UiPageProvider;
import org.keycloak.services.ui.extend.UiPageProviderFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pagina administrativa declarativa do Keycloak.
 *
 * Cria um ComponentModel por client. A configuracao de cada item define quais
 * eventos daquele client geram webhook.
 */
public final class ClientWebhookAdminPage implements UiPageProvider, UiPageProviderFactory<ComponentModel> {

    @Override
    public String getId() {
        return ClientWebhookConfigKeys.PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Configure quais eventos de cada client devem ser enviados por webhook ao sistema integrado.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(ClientWebhookConfigKeys.CLIENT_ID)
                    .label("Client")
                    .helpText("Client OIDC/SAML que representa o sistema integrado.")
                    .type(ProviderConfigProperty.CLIENT_LIST_TYPE)
                    .required(true)
                    .add()
                .property()
                    .name(ClientWebhookConfigKeys.ENABLED)
                    .label("Webhook ativo")
                    .helpText("Quando desabilitado, nenhum webhook sera enviado para este client.")
                    .type(ProviderConfigProperty.BOOLEAN_TYPE)
                    .defaultValue(false)
                    .add()
                .property()
                    .name(ClientWebhookConfigKeys.EVENTS)
                    .label("Eventos monitorados")
                    .helpText("Selecione os eventos que devem disparar o webhook para este client.")
                    .type(ProviderConfigProperty.MULTIVALUED_LIST_TYPE)
                    .options(WebhookEventType.supportedCodes())
                    .required(true)
                    .add()
                .property()
                    .name(ClientWebhookConfigKeys.ROLES)
                    .label("Roles monitoradas")
                    .helpText("Usado apenas em ROLE_REVOKED e ROLE_GRANTED. Informe client roles exatas ou * para todas.")
                    .type(ProviderConfigProperty.MULTIVALUED_STRING_TYPE)
                    .required(false)
                    .add()
                .property()
                    .name(ClientWebhookConfigKeys.URL)
                    .label("URI do webhook")
                    .helpText("Endpoint HTTPS do sistema integrado que recebera um webhook_token (JWT assinado).")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .required(true)
                    .add()
                .property()
                    .name(ClientWebhookConfigKeys.TIMEOUT_MS)
                    .label("Timeout (ms)")
                    .helpText("Tempo maximo por tentativa de chamada HTTP.")
                    .type(ProviderConfigProperty.INTEGER_TYPE)
                    .defaultValue(ClientWebhookConfigKeys.DEFAULT_TIMEOUT_MS)
                    .add()
                .property()
                    .name(ClientWebhookConfigKeys.MAX_ATTEMPTS)
                    .label("Tentativas")
                    .helpText("Quantidade maxima de tentativas para timeout, HTTP 408, 429 ou 5xx.")
                    .type(ProviderConfigProperty.INTEGER_TYPE)
                    .defaultValue(ClientWebhookConfigKeys.DEFAULT_MAX_ATTEMPTS)
                    .add()
                .build();
    }

    @Override
    public Map<String, Object> getTypeMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("displayFields", List.of(
                ClientWebhookConfigKeys.CLIENT_ID,
                ClientWebhookConfigKeys.ENABLED,
                ClientWebhookConfigKeys.EVENTS,
                ClientWebhookConfigKeys.URL));
        return metadata;
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model)
            throws ComponentValidationException {

        String clientId = required(model, ClientWebhookConfigKeys.CLIENT_ID, "Client obrigatorio.");
        String url = required(model, ClientWebhookConfigKeys.URL, "URI do webhook obrigatoria.");

        ClientModel client = realm.getClientByClientId(clientId);
        if (client == null) {
            throw new ComponentValidationException("Client nao encontrado: " + clientId);
        }

        validateUniqueClientConfiguration(realm, model, clientId);
        validateUrl(url);

        Set<WebhookEventType> selectedEvents = readSelectedEvents(model);
        if (selectedEvents.isEmpty()) {
            throw new ComponentValidationException("Selecione pelo menos um evento monitorado.");
        }

        boolean requiresRoles = selectedEvents.stream().anyMatch(WebhookEventType::isRoleEvent);
        validateRoles(client, model, requiresRoles);

        int timeout = model.get(
                ClientWebhookConfigKeys.TIMEOUT_MS,
                ClientWebhookConfigKeys.DEFAULT_TIMEOUT_MS);
        if (timeout < ClientWebhookConfigKeys.MIN_TIMEOUT_MS
                || timeout > ClientWebhookConfigKeys.MAX_TIMEOUT_MS) {
            throw new ComponentValidationException(
                    "Timeout deve estar entre "
                            + ClientWebhookConfigKeys.MIN_TIMEOUT_MS
                            + " e "
                            + ClientWebhookConfigKeys.MAX_TIMEOUT_MS
                            + " ms.");
        }

        int attempts = model.get(
                ClientWebhookConfigKeys.MAX_ATTEMPTS,
                ClientWebhookConfigKeys.DEFAULT_MAX_ATTEMPTS);
        if (attempts < ClientWebhookConfigKeys.MIN_ATTEMPTS
                || attempts > ClientWebhookConfigKeys.MAX_ALLOWED_ATTEMPTS) {
            throw new ComponentValidationException(
                    "Tentativas deve estar entre "
                            + ClientWebhookConfigKeys.MIN_ATTEMPTS
                            + " e "
                            + ClientWebhookConfigKeys.MAX_ALLOWED_ATTEMPTS
                            + ".");
        }
    }

    private static Set<WebhookEventType> readSelectedEvents(ComponentModel model) {
        List<String> rawEvents = model.getConfig().get(ClientWebhookConfigKeys.EVENTS);
        Set<WebhookEventType> result = new LinkedHashSet<>();

        if (rawEvents == null || rawEvents.isEmpty()) {
            return result;
        }

        for (String raw : rawEvents) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                result.add(WebhookEventType.valueOf(raw.trim()));
            } catch (IllegalArgumentException e) {
                throw new ComponentValidationException("Evento nao suportado: " + raw);
            }
        }

        return result;
    }

    private static void validateUniqueClientConfiguration(
            RealmModel realm,
            ComponentModel current,
            String clientId) {

        boolean duplicate = realm.getComponentsStream(realm.getId(), UiPageProvider.class.getName())
                .filter(component -> ClientWebhookConfigKeys.PROVIDER_ID.equals(component.getProviderId()))
                .filter(component -> !Objects.equals(component.getId(), current.getId()))
                .anyMatch(component -> clientId.equals(component.get(ClientWebhookConfigKeys.CLIENT_ID)));

        if (duplicate) {
            throw new ComponentValidationException(
                    "Ja existe uma configuracao de webhook para o client " + clientId + ".");
        }
    }

    private static void validateUrl(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) || uri.getHost() == null
                    || uri.getRawFragment() != null) {
                throw new ComponentValidationException("A URI deve ser HTTPS, possuir host valido e nao conter fragmento.");
            }
            if (uri.getUserInfo() != null) {
                throw new ComponentValidationException("Nao informe usuario/senha diretamente na URI do webhook.");
            }
        } catch (URISyntaxException e) {
            throw new ComponentValidationException("URI do webhook invalida.", e);
        }
    }

    private static void validateRoles(ClientModel client, ComponentModel model, boolean required) {
        List<String> rawRoles = model.getConfig().get(ClientWebhookConfigKeys.ROLES);
        Set<String> roles = new LinkedHashSet<>();

        if (rawRoles != null) {
            rawRoles.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(roles::add);
        }

        if (roles.isEmpty()) {
            if (required) {
                throw new ComponentValidationException(
                        "Informe pelo menos uma client role para ROLE_REVOKED/ROLE_GRANTED.");
            }
            return;
        }

        if (roles.contains("*")) {
            if (roles.size() > 1) {
                throw new ComponentValidationException("Use * sozinho ou informe roles especificas.");
            }
            return;
        }

        for (String roleName : roles) {
            if (client.getRole(roleName) == null) {
                throw new ComponentValidationException(
                        "A role '" + roleName + "' nao existe no client " + client.getClientId() + ".");
            }
        }
    }

    private static String required(ComponentModel model, String key, String message) {
        String value = model.get(key);
        if (value == null || value.isBlank()) {
            throw new ComponentValidationException(message);
        }
        return value.trim();
    }

    @Override
    public void init(Config.Scope config) {
        // Sem configuracao global.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Sem inicializacao adicional.
    }

    @Override
    public void close() {
        // Nada a fechar.
    }
}
