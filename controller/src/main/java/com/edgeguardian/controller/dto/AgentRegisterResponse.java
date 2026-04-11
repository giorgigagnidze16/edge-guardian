package com.edgeguardian.controller.dto;

import java.util.Map;

/**
 * Registration response sent to the agent.
 * Matches the agent's model.RegisterResponse JSON shape.
 */
public record AgentRegisterResponse(
        boolean accepted,
        String message,
        Map<String, Object> initialManifest,
        String deviceToken,
        String caCertPem
) {
    public static AgentRegisterResponse error(String message) {
        return new AgentRegisterResponse(false, message, null, null, null);
    }
}
