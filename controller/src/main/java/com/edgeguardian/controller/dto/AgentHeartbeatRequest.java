package com.edgeguardian.controller.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Heartbeat request sent by the agent.
 * Matches the agent's model.HeartbeatRequest JSON shape.
 */
public record AgentHeartbeatRequest(
        String deviceId,
        String agentVersion,
        Map<String, Object> status,
        Instant timestamp
) {}
