package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
