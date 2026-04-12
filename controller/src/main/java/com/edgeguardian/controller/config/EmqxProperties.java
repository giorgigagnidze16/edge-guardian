package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the EMQX admin integration (kickout on revocation).
 *
 * <p>If {@code adminUrl} is blank, the admin client operates in a no-op mode so the
 * controller still works without an EMQX dashboard reachable (e.g. in unit tests).
 *
 * @param adminUrl  base URL of the EMQX v5 REST API, e.g. {@code http://emqx:18083/api/v5}
 * @param username  dashboard user with client-management permission
 * @param password  dashboard password
 * @param timeoutMs connect/read timeout for admin calls
 */
@ConfigurationProperties(prefix = "edgeguardian.controller.emqx.admin")
public record EmqxProperties(
        String adminUrl,
        String username,
        String password,
        int timeoutMs
) {
    public EmqxProperties {
        if (timeoutMs <= 0) {
            timeoutMs = 3000;
        }
    }

    public boolean isConfigured() {
        return adminUrl != null && !adminUrl.isBlank();
    }
}
