package com.edgeguardian.controller.dto;

import java.util.Map;

/**
 * Registration request sent by the agent.
 * Matches the agent's model.RegisterRequest JSON shape.
 */
public record AgentRegisterRequest(
        String deviceId,
        String hostname,
        String architecture,
        String os,
        String agentVersion,
        Map<String, String> labels
) {}
