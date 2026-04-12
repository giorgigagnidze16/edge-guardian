package com.edgeguardian.controller.mqtt.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CertRequestPayload(
        String deviceId,
        String name,
        String commonName,
        List<String> sans,
        String csrPem,
        String type,
        String currentSerial
) {}
