package com.edgeguardian.controller.mqtt.payload;

public record CertResponsePayload(
        String name,
        boolean accepted,
        String message,
        String certPem,
        String caCertPem
) {}
