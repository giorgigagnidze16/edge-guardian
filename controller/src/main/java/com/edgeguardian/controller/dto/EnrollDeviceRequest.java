package com.edgeguardian.controller.dto;

import java.util.Map;

public record EnrollDeviceRequest(
        String enrollmentToken,
        String deviceId,
        String hostname,
        String architecture,
        String os,
        String agentVersion,
        Map<String, String> labels
) {}
