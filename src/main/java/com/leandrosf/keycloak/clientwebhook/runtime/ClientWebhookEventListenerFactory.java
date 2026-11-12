package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.config.ClientWebhookConfig;
import com.leandrosf.keycloak.clientwebhook.config.ClientWebhookConfigRepository;
import com.leandrosf.keycloak.clientwebhook.config.ClientWebhookTransportPolicy;
import com.leandrosf.keycloak.clientwebhook.config.WebhookEventType;
import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Listener global que combina:
 *
 * 1) EventListenerProvider para eventos de usuario nativos do Keycloak
 *    (LOGIN, LOGOUT, REGISTER etc.);
 * 2) ProviderEventListener para eventos internos que carregam diretamente
 *    UserModel/RoleModel (ROLE_GRANTED, ROLE_REVOKED e USER_REMOVED).
 */
public final class ClientWebhookEventListenerFactory implements EventListenerProviderFactory {

    public static final String ID = "client-webhook-runtime";

    private static final Logger LOG = Logger.getLogger(ClientWebhookEventListenerFactory.class);

    private KeycloakSessionFactory sessionFactory;
    private ProviderEventListener providerEventListener;
    private ClientWebhookDispatcher dispatcher;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new ClientUserEventListener(session, this);
    }

    @Override
    public void init(Config.Scope config) {
        dispatcher = new ClientWebhookDispatcher();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        this.sessionFactory = factory;
        this.providerEventListener = this::onProviderEvent;
        factory.register(providerEventListener);
        LOG.info("Client Webhook inicializado");
    }

    @Override
    public void close() {
        if (sessionFactory != null && providerEventListener != null) {
            sessionFactory.unregister(providerEventListener);
        }
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public boolean isGlobal() {
        return true;
    }

    private void onProviderEvent(ProviderEvent event) {
        try {
            if (event instanceof RoleModel.RoleRevokedEvent revokedEvent) {
                handleRoleEvent(
                        WebhookEventType.ROLE_REVOKED,
                        revokedEvent.getRole(),
                        revokedEvent.getUser(),
                        revokedEvent.getRealm(),
                        revokedEvent.getKeycloakSession());
                return;
            }

            if (event instanceof RoleModel.RoleGrantedEvent grantedEvent) {
                handleRoleEvent(
                        WebhookEventType.ROLE_GRANTED,
                        grantedEvent.getRole(),
                        grantedEvent.getUser(),
                        grantedEvent.getRealm(),
                        grantedEvent.getKeycloakSession());
                return;
            }

            if (event instanceof UserModel.UserPreRemovedEvent userPreRemovedEvent) {
                handleUserRemoved(userPreRemovedEvent);
            }
        } catch (RuntimeException e) {
            // Nunca derrubar a operacao principal do Keycloak por falha de integracao.
            LOG.error("Erro ao processar ProviderEvent para webhook", e);
        }
    }

    private void handleRoleEvent(
            WebhookEventType eventType,
            RoleModel role,
            UserModel user,
            RealmModel realm,
            KeycloakSession session) {

        if (role == null || !role.isClientRole() || user == null || realm == null || session == null) {
            return;
        }

        ClientModel client = realm.getClientById(role.getContainerId());
        if (client == null) {
            LOG.warnf(
                    "%s recebido para client inexistente. role=%s containerId=%s",
                    eventType.name(), role.getName(), role.getContainerId());
            return;
        }

        Optional<ClientWebhookConfig> optionalConfig =
                ClientWebhookConfigRepository.findByClientId(realm, client.getClientId());

        if (optionalConfig.isEmpty()) {
            return;
        }

        ClientWebhookConfig config = optionalConfig.get();
        if (!config.enabled()
                || !config.monitorsEvent(eventType)
                || !config.monitorsRole(role.getName())) {
            return;
        }

        ClientWebhookPayload payload = new ClientWebhookPayload(
                UUID.randomUUID().toString(),
                eventType.name(),
                Instant.now().toString(),
                ClientWebhookPayload.SOURCE_PROVIDER_EVENT,
                realm.getName(),
                snapshotClient(client),
                snapshotUser(user.getId(), user),
                new ClientWebhookPayload.RoleInfo(role.getId(), role.getName()),
                null);

        enqueueAfterCommit(session, realm, List.of(new PendingWebhook(config, payload)));
    }

    private void handleUserRemoved(UserModel.UserPreRemovedEvent event) {
        KeycloakSession session = event.getKeycloakSession();
        RealmModel realm = event.getRealm();
        UserModel user = event.getUser();

        if (session == null || realm == null || user == null) {
            return;
        }

        ClientWebhookPayload.UserInfo userSnapshot = snapshotUser(user.getId(), user);
        List<PendingWebhook> pending = new ArrayList<>();

        for (ClientWebhookConfig config : ClientWebhookConfigRepository.findAll(realm)) {
            if (!config.enabled() || !config.monitorsEvent(WebhookEventType.USER_REMOVED)) {
                continue;
            }

            ClientModel client = realm.getClientByClientId(config.clientId());
            if (client == null) {
                LOG.warnf(
                        "Configuracao USER_REMOVED ignorada porque o client nao existe mais. clientId=%s",
                        config.clientId());
                continue;
            }

            ClientWebhookPayload payload = new ClientWebhookPayload(
                    UUID.randomUUID().toString(),
                    WebhookEventType.USER_REMOVED.name(),
                    Instant.now().toString(),
                    ClientWebhookPayload.SOURCE_PROVIDER_EVENT,
                    realm.getName(),
                    snapshotClient(client),
                    userSnapshot,
                    null,
                    null);

            pending.add(new PendingWebhook(config, payload));
        }

        enqueueAfterCommit(session, realm, pending);
    }

    private void handleUserEvent(KeycloakSession session, Event event) {
        try {
            Optional<WebhookEventType> optionalEventType =
                    WebhookEventType.fromKeycloakEventType(event.getType());

            if (optionalEventType.isEmpty()) {
                return;
            }

            WebhookEventType eventType = optionalEventType.get();
            String realmId = trimToNull(event.getRealmId());
            String eventClientId = trimToNull(event.getClientId());
            String userId = trimToNull(event.getUserId());

            // Esta versao envia eventos de usuario apenas quando o Keycloak informa
            // explicitamente client e usuario do evento.
            if (realmId == null || eventClientId == null || userId == null) {
                return;
            }

            RealmModel realm = session.realms().getRealm(realmId);
            if (realm == null) {
                return;
            }

            ClientModel client = resolveClient(realm, eventClientId);
            if (client == null) {
                return;
            }

            Optional<ClientWebhookConfig> optionalConfig =
                    ClientWebhookConfigRepository.findByClientId(realm, client.getClientId());

            if (optionalConfig.isEmpty()) {
                return;
            }

            ClientWebhookConfig config = optionalConfig.get();
            if (!config.enabled() || !config.monitorsEvent(eventType)) {
                return;
            }

            UserModel user = session.users().getUserById(realm, userId);

            long eventTime = event.getTime();
            String occurredAt = eventTime > 0
                    ? Instant.ofEpochMilli(eventTime).toString()
                    : Instant.now().toString();

            ClientWebhookPayload payload = new ClientWebhookPayload(
                    UUID.randomUUID().toString(),
                    eventType.name(),
                    occurredAt,
                    ClientWebhookPayload.SOURCE_USER_EVENT,
                    realm.getName(),
                    snapshotClient(client),
                    snapshotUser(userId, user),
                    null,
                    new ClientWebhookPayload.ContextInfo(
                            event.getIpAddress(),
                            event.getSessionId()));

            enqueueAfterCommit(session, realm, List.of(new PendingWebhook(config, payload)));

        } catch (RuntimeException e) {
            LOG.errorf(e,
                    "Erro ao processar evento de usuario para webhook. eventType=%s client=%s user=%s",
                    event.getType(), event.getClientId(), event.getUserId());
        }
    }

    private void enqueueAfterCommit(KeycloakSession session, RealmModel realm, List<PendingWebhook> pending) {
        if (session == null || realm == null || pending == null || pending.isEmpty()) {
            return;
        }

        // Sign while the request/realm context is still available. Post-commit
        // callbacks and worker threads must never use KeycloakSession or UserModel.
        List<SignedPendingWebhook> signed = new ArrayList<>();
        for (PendingWebhook item : pending) {
            try {
                if (!ClientWebhookTransportPolicy.isAllowedEndpoint(item.config().url())) {
                    LOG.errorf("Webhook nao enviado: URL nao permitida pela politica de transporte. client=%s eventId=%s",
                            item.payload().client().clientId(), item.payload().eventId());
                    continue;
                }
                String jwt = WebhookJwtSigner.sign(session, realm, item.payload());
                signed.add(new SignedPendingWebhook(item.config(), item.payload(), jwt));
            } catch (RuntimeException ex) {
                // Fail closed: never fall back to unsigned JSON after signing fails.
                LOG.errorf(ex,
                        "Nao foi possivel assinar webhook. eventType=%s client=%s eventId=%s",
                        item.payload().eventType(), item.payload().client().clientId(),
                        item.payload().eventId());
            }
        }
        if (signed.isEmpty()) {
            return;
        }

        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override
            protected void commitImpl() {
                for (SignedPendingWebhook item : signed) {
                    try {
                        dispatcher.dispatchAsync(item.config(), item.payload(), item.jwt());
                    } catch (RuntimeException e) {
                        LOG.errorf(e,
                                "Nao foi possivel agendar webhook apos commit. eventType=%s client=%s eventId=%s",
                                item.payload().eventType(),
                                item.payload().client().clientId(),
                                item.payload().eventId());
                    }
                }
            }

            @Override
            protected void rollbackImpl() {
                for (SignedPendingWebhook item : signed) {
                    LOG.debugf(
                            "Webhook descartado por rollback. eventType=%s client=%s eventId=%s",
                            item.payload().eventType(),
                            item.payload().client().clientId(),
                            item.payload().eventId());
                }
            }
        });
    }

    private static ClientModel resolveClient(RealmModel realm, String eventClientId) {
        ClientModel client = realm.getClientByClientId(eventClientId);
        if (client == null) {
            client = realm.getClientById(eventClientId);
        }
        return client;
    }

    private static ClientWebhookPayload.ClientInfo snapshotClient(ClientModel client) {
        return new ClientWebhookPayload.ClientInfo(client.getId(), client.getClientId());
    }

    private static ClientWebhookPayload.UserInfo snapshotUser(String fallbackUserId, UserModel user) {
        if (user == null) {
            return new ClientWebhookPayload.UserInfo(
                    fallbackUserId,
                    null,
                    null,
                    null,
                    null);
        }

        return new ClientWebhookPayload.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PendingWebhook(
            ClientWebhookConfig config,
            ClientWebhookPayload payload) {
    }

    private record SignedPendingWebhook(
            ClientWebhookConfig config,
            ClientWebhookPayload payload,
            String jwt) {
    }

    private static final class ClientUserEventListener implements EventListenerProvider {

        private final KeycloakSession session;
        private final ClientWebhookEventListenerFactory factory;

        private ClientUserEventListener(
                KeycloakSession session,
                ClientWebhookEventListenerFactory factory) {
            this.session = session;
            this.factory = factory;
        }

        @Override
        public void onEvent(Event event) {
            factory.handleUserEvent(session, event);
        }

        @Override
        public void onEvent(AdminEvent event, boolean includeRepresentation) {
            // AdminEvent nao e usado nesta versao.
        }

        @Override
        public void close() {
            // Nada a fechar por request.
        }
    }
}
