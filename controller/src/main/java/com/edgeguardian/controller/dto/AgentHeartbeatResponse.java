package com.edgeguardian.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * Heartbeat response sent to the agent.
 * Matches the agent's model.HeartbeatResponse JSON shape.
 */
public record AgentHeartbeatResponse(
        boolean manifestUpdated,
        Map<String, Object> manifest,
        List<Map<String, Object>> pendingCommands
) {}
