package com.edgeguardian.controller.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnrollRequestPayload(
        String enrollmentToken,
        String deviceId,
        String hostname,
        String architecture,
        String os,
        String agentVersion,
        Map<String, String> labels,
        String csrPem,
        String commonName,
        List<String> sans
) {
}
