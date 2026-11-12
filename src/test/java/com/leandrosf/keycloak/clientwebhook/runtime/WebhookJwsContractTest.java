package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.junit.jupiter.api.Test;
import org.keycloak.crypto.SignatureException;
import org.keycloak.crypto.SignatureSignerContext;
import org.keycloak.jose.jws.JWSBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class WebhookJwsContractTest {
    @Test
    void generatedTokenHasExclusiveTypeKeyIdAndVerifiableSignature() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        SignatureSignerContext signer = new SignatureSignerContext() {
            @Override public String getKid() { return "test-kid"; }
            @Override public String getAlgorithm() { return "RS256"; }
            @Override public String getHashAlgorithm() { return "SHA-256"; }
            @Override public byte[] sign(byte[] data) throws SignatureException {
                try {
                    Signature signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(pair.getPrivate());
                    signature.update(data);
                    return signature.sign();
                } catch (Exception e) {
                    throw new SignatureException("Test signing failed", e);
                }
            }
        };
        ClientWebhookPayload payload = new ClientWebhookPayload(
                "event-uuid", "ROLE_REVOKED", "2026-11-12T12:00:00Z", "PROVIDER_EVENT",
                "portal-acesso", new ClientWebhookPayload.ClientInfo("client-uuid", "java_esegtrab"),
                new ClientWebhookPayload.UserInfo("user-uuid", "12345678901", null, null, null),
                new ClientWebhookPayload.RoleInfo("role-uuid", "role_java_esegtrab"), null);

        String jwt = new JWSBuilder().type(WebhookJwtSigner.TOKEN_TYPE)
                .jsonContent(WebhookJwtClaims.create(payload,
                        "https://sso.example.org/realms/portal-acesso", 1800000000L))
                .sign(signer);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String body = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(header.contains("\"alg\":\"RS256\""));
        assertTrue(header.contains("\"kid\":\"test-kid\""));
        assertTrue(header.contains("\"typ\":\"webhook+jwt\""));
        assertTrue(body.contains("\"token_use\":\"client_webhook\""));
        assertTrue(body.contains("\"username\":\"12345678901\""));

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }
}
