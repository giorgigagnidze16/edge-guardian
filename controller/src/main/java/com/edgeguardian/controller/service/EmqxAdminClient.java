package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.EmqxProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around the EMQX v5 dashboard REST API for live session management.
 *
 * <p>Currently used for one thing: force-disconnecting a client after its device token or
 * certificate has been revoked. Without this call, the agent's existing MQTT session would
 * survive until the next TCP reset since CRL refresh only affects *new* TLS handshakes.
 *
 * <p>Failure is logged and swallowed — if the broker is temporarily unreachable, the DB
 * revocation is still the source of truth and the kickout will simply not happen. The next
 * CRL refresh will reject any reconnect attempt anyway.
 */
@Slf4j
@Service
@EnableConfigurationProperties(EmqxProperties.class)
public class EmqxAdminClient {

    private final EmqxProperties properties;
    private final HttpClient httpClient;
    private final String basicAuthHeader;

    public EmqxAdminClient(EmqxProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
        this.basicAuthHeader = buildBasicAuth(properties.username(), properties.password());
    }

    /**
     * Force-disconnect the MQTT client identified by {@code clientId} (typically the device ID).
     * No-op if EMQX admin integration is not configured. Never throws.
     */
    public void kickout(String clientId) {
        if (!properties.isConfigured()) {
            log.debug("EMQX admin not configured — skipping kickout for {}", clientId);
            return;
        }

        URI uri = URI.create(properties.adminUrl() + "/clients/" + clientId + "/kickout");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", basicAuthHeader)
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 204 || status == 200) {
                log.info("EMQX kickout OK for client {}", clientId);
            } else if (status == 404) {
                log.debug("EMQX kickout: no active session for client {}", clientId);
            } else {
                log.warn("EMQX kickout for {} returned HTTP {} — body: {}",
                        clientId, status, response.body());
            }
        } catch (Exception e) {
            log.warn("EMQX kickout failed for {} ({}): {}", clientId, uri, e.getMessage());
        }
    }

    private static String buildBasicAuth(String user, String pass) {
        if (user == null || pass == null) {
            return "";
        }
        String token = Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }
}
