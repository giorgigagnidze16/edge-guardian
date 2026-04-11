package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edgeguardian.controller.ca")
public record CaProperties(
        String encryptionKey,
        int caValidityDays,
        int certValidityDays,
        int renewBeforeDays
) {}
