package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed view of {@code edgeguardian.controller.mqtt.*}. Replaces the scattered
 * {@code @Value} annotations previously duplicated across {@code MqttConfig}
 * and every listener.
 */
@ConfigurationProperties(prefix = "edgeguardian.controller.mqtt")
public record MqttProperties(
        String brokerUrl,
        String clientId,
        String username,
        String password,
        String topicRoot,
        Duration sessionExpiry,
        Duration keepAlive,
        Duration connectTimeout
) {
    public MqttProperties {
        if (brokerUrl == null || brokerUrl.isBlank())
            brokerUrl = "tcp://localhost:1883";
        if (clientId == null || clientId.isBlank())
            clientId = "edgeguardian-controller";
        if (topicRoot == null || topicRoot.isBlank())
            topicRoot = "edgeguardian";
        if (sessionExpiry == null)
            sessionExpiry = Duration.ofDays(1);
        if (keepAlive == null)
            keepAlive = Duration.ofSeconds(60);
        if (connectTimeout == null)
            connectTimeout = Duration.ofSeconds(10);
    }
}
