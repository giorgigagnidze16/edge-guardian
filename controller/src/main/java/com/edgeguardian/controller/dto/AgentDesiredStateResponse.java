package com.edgeguardian.controller.dto;

import java.util.Map;

/**
 * Desired state response sent to the agent.
 * Matches the agent's model.DesiredStateResponse JSON shape.
 */
public record AgentDesiredStateResponse(
        Map<String, Object> manifest,
        long version
) {}
