package com.edgeguardian.controller.dto;

/**
 * Acknowledgement sent to the agent after a state report.
 * Matches the agent's model.ReportStateResponse JSON shape.
 */
public record AgentReportStateResponse(
        boolean acknowledged
) {}
