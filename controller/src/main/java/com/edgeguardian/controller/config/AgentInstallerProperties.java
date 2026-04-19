package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edgeguardian.controller.agent-installer")
public record AgentInstallerProperties(
        String controllerUrl,
        String brokerUrl,
        String mtlsBrokerUrl,
        String bootstrapPassword,
        String agentVersion
) {}
