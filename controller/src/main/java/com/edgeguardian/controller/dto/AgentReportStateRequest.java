package com.edgeguardian.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * State report sent by the agent.
 * Matches the agent's model.ReportStateRequest JSON shape.
 */
public record AgentReportStateRequest(
        String deviceId,
        Map<String, Object> status,
        List<Map<String, Object>> pluginStates,
        Instant timestamp
) {}
