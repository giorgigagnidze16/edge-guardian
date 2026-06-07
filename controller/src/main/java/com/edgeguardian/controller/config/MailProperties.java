package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound-email settings. {@code appUrl} is the dashboard base used to build
 * the sign-in link in invitation emails.
 */
@ConfigurationProperties(prefix = "edgeguardian.controller.mail")
public record MailProperties(
        boolean enabled,
        String from,
        String appUrl
) {
}
