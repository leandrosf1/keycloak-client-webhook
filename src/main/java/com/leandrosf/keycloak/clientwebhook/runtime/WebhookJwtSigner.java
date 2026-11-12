package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.keycloak.crypto.SignatureException;
import org.keycloak.crypto.SignatureProvider;
import org.keycloak.crypto.SignatureSignerContext;
import org.keycloak.jose.jws.JWSBuilder;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.Urls;
import org.keycloak.urls.UrlType;

import java.time.Instant;
import java.util.Objects;

/** Signs the webhook once inside the Keycloak request/transaction. */
public final class WebhookJwtSigner {

    public static final String ALGORITHM = "RS256";
    public static final String TOKEN_TYPE = "webhook+jwt";

    private WebhookJwtSigner() {
    }

    public static String sign(KeycloakSession session, RealmModel realm, ClientWebhookPayload event) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(realm, "realm");

        // The active signing key is resolved using the realm on the Keycloak session.
        // Never risk using a key from a different realm.
        RealmModel contextRealm = session.getContext().getRealm();
        if (contextRealm == null || !realm.getId().equals(contextRealm.getId())) {
            throw new IllegalStateException("JWT signing context does not match event realm");
        }

        String issuer = Urls.realmIssuer(
                session.getContext().getUri(UrlType.FRONTEND).getBaseUri(), realm.getName());
        SignatureProvider provider = session.getProvider(SignatureProvider.class, ALGORITHM);
        if (provider == null || !provider.isAsymmetricAlgorithm()) {
            throw new IllegalStateException("RS256 realm signing provider is unavailable");
        }

        try {
            SignatureSignerContext signer = provider.signer();
            if (!ALGORITHM.equals(signer.getAlgorithm())
                    || signer.getKid() == null || signer.getKid().isBlank()) {
                throw new IllegalStateException("No active RS256 realm signing key with kid");
            }

            return new JWSBuilder()
                    .type(TOKEN_TYPE)
                    .jsonContent(WebhookJwtClaims.create(
                            event, issuer, Instant.now().getEpochSecond()))
                    .sign(signer);
        } catch (SignatureException ex) {
            throw new IllegalStateException("Unable to sign client webhook JWT", ex);
        }
    }
}
