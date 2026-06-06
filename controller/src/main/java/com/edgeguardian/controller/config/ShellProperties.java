package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed view of {@code edgeguardian.controller.shell.*} - limits and timeouts
 * for interactive device shell sessions.
 */
@ConfigurationProperties(prefix = "edgeguardian.controller.shell")
public record ShellProperties(
        Integer maxSessionsPerDevice,
        Integer maxSessionsPerOrg,
        Duration idleTimeout,
        Duration maxDuration,
        Duration ticketTtl
) {
    public ShellProperties {
        if (maxSessionsPerDevice == null || maxSessionsPerDevice <= 0) maxSessionsPerDevice = 2;
        if (maxSessionsPerOrg == null || maxSessionsPerOrg <= 0) maxSessionsPerOrg = 10;
        if (idleTimeout == null) idleTimeout = Duration.ofMinutes(15);
        if (maxDuration == null) maxDuration = Duration.ofMinutes(60);
        if (ticketTtl == null) ticketTtl = Duration.ofSeconds(30);
    }
}
