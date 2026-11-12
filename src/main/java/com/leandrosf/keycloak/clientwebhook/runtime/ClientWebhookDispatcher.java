package com.leandrosf.keycloak.clientwebhook.runtime;

import com.leandrosf.keycloak.clientwebhook.config.ClientWebhookConfig;
import com.leandrosf.keycloak.clientwebhook.model.ClientWebhookPayload;
import org.jboss.logging.Logger;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClientWebhookDispatcher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ClientWebhookDispatcher.class);
    private static final String USER_AGENT = "Keycloak-Client-Webhook/1.2.0-dev";

    private final ExecutorService executor;
    private final HttpClient httpClient;

    public ClientWebhookDispatcher() {
        this.executor = Executors.newFixedThreadPool(2, new DaemonThreadFactory());
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void dispatchAsync(ClientWebhookConfig config, ClientWebhookPayload payload, String signedJwt) {
        if (config.url() == null || !"https".equalsIgnoreCase(config.url().getScheme())) {
            LOG.errorf("Webhook nao enviado: HTTPS obrigatorio. eventId=%s", payload.eventId());
            return;
        }
        if (signedJwt == null || signedJwt.isBlank()) {
            LOG.errorf("Webhook nao enviado: JWT ausente. eventId=%s", payload.eventId());
            return;
        }
        executor.submit(() -> send(config, payload, signedJwt));
    }

    private void send(ClientWebhookConfig config, ClientWebhookPayload payload, String signedJwt) {
        final String body;
        try {
            body = JsonSerialization.writeValueAsString(Map.of("webhook_token", signedJwt));
        } catch (IOException e) {
            LOG.errorf(e,
                    "Nao foi possivel serializar webhook. eventType=%s realm=%s client=%s eventId=%s",
                    payload.eventType(), payload.realm(), payload.client().clientId(), payload.eventId());
            return;
        }

        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(config.url())
                        .timeout(Duration.ofMillis(config.timeoutMs()))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .header("X-Webhook-Event-Id", payload.eventId())
                        .header("X-Keycloak-Event-Type", payload.eventType())
                        .header("X-Keycloak-Realm", payload.realm())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<Void> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.discarding());

                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    LOG.infof(
                            "Webhook entregue. eventType=%s realm=%s client=%s user=%s status=%d eventId=%s",
                            payload.eventType(),
                            payload.realm(),
                            payload.client().clientId(),
                            username(payload),
                            status,
                            payload.eventId());
                    return;
                }

                if (!isRetryableStatus(status) || attempt == config.maxAttempts()) {
                    LOG.errorf(
                            "Webhook falhou. eventType=%s realm=%s client=%s user=%s status=%d tentativa=%d/%d eventId=%s",
                            payload.eventType(),
                            payload.realm(),
                            payload.client().clientId(),
                            username(payload),
                            status,
                            attempt,
                            config.maxAttempts(),
                            payload.eventId());
                    return;
                }

                LOG.warnf(
                        "Webhook retornou status temporario. eventType=%s client=%s status=%d tentativa=%d/%d eventId=%s",
                        payload.eventType(),
                        payload.client().clientId(),
                        status,
                        attempt,
                        config.maxAttempts(),
                        payload.eventId());

                if (!sleepBeforeRetry(attempt)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnf(
                        "Envio do webhook interrompido. eventType=%s client=%s eventId=%s",
                        payload.eventType(), payload.client().clientId(), payload.eventId());
                return;
            } catch (IOException | RuntimeException e) {
                if (attempt == config.maxAttempts()) {
                    LOG.errorf(e,
                            "Erro no envio do webhook. eventType=%s client=%s tentativa=%d/%d eventId=%s",
                            payload.eventType(), payload.client().clientId(), attempt, config.maxAttempts(), payload.eventId());
                    return;
                }

                LOG.warnf(e,
                        "Erro temporario no envio do webhook. eventType=%s client=%s tentativa=%d/%d eventId=%s",
                        payload.eventType(), payload.client().clientId(), attempt, config.maxAttempts(), payload.eventId());
                if (!sleepBeforeRetry(attempt)) {
                    return;
                }
            }
        }
    }

    private static String username(ClientWebhookPayload payload) {
        return payload.user() == null ? null : payload.user().username();
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static boolean sleepBeforeRetry(int attempt) {
        long delayMs = Math.min(2_000L, 250L * (1L << Math.max(0, attempt - 1)));
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Thread de envio do webhook foi interrompida durante espera para nova tentativa.");
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "keycloak-client-webhook-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
