package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.EmqxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Slf4j
@Service
@EnableConfigurationProperties(EmqxProperties.class)
public class EmqxAdminClient {

    private static final int MAX_ATTEMPTS = 2;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final int[] KICK_SCHEDULE_SEC = {0, 3, 8, 16, 30, 45};

    private final EmqxProperties properties;
    private final HttpClient httpClient;
    private final String basicAuthHeader;
    private final TaskScheduler taskScheduler;

    public EmqxAdminClient(EmqxProperties properties, TaskScheduler taskScheduler) {
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
        this.basicAuthHeader = buildBasicAuth(properties.username(), properties.password());
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String buildBasicAuth(String user, String pass) {
        if (user == null || pass == null) {
            return "";
        }
        String token = Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    /**
     * Kick the client now and again over the CRL-refresh window, so a device
     * that reconnects with a just-revoked cert is repeatedly evicted until the
     * broker's CRL update rejects it at the TLS handshake.
     */
    public void kickoutPersistent(String clientId) {
        if (!properties.isConfigured()) {
            return;
        }
        Instant now = Instant.now();
        for (int delay : KICK_SCHEDULE_SEC) {
            taskScheduler.schedule(() -> {
                try {
                    kickout(clientId);
                } catch (RuntimeException e) {
                    log.warn("Scheduled kickout failed for {}: {}", clientId, e.getMessage());
                }
            }, now.plusSeconds(delay));
        }
    }

    public void kickout(String clientId) {
        if (!properties.isConfigured()) {
            log.debug("EMQX admin not configured - skipping kickout for {}", clientId);
            return;
        }

        URI uri = URI.create(properties.adminUrl() + "/clients/" + clientId + "/kickout");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", basicAuthHeader)
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        Exception lastErr = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 204 || status == 200) {
                    log.info("EMQX kickout OK for client {} (attempt {})", clientId, attempt);
                    return;
                }
                if (status == 404) {
                    log.debug("EMQX kickout: no active session for client {}", clientId);
                    return;
                }
                // 400/401/403 indicate config problems that retrying won't fix.
                if (status == 400 || status == 401 || status == 403) {
                    log.error("EMQX kickout for {} returned HTTP {} (non-retryable) - body: {}",
                            clientId, status, truncate(response.body()));
                    return;
                }
                lastErr = new RuntimeException("HTTP " + status + " - " + truncate(response.body()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("EMQX kickout interrupted for {}", clientId);
                return;
            } catch (Exception e) {
                lastErr = e;
            }

            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("EMQX kickout failed for {} after {} attempts ({}): {}",
                clientId, MAX_ATTEMPTS, uri,
                lastErr != null ? lastErr.getMessage() : "unknown");
    }
}
