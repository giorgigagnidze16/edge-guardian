package com.edgeguardian.controller.mqtt.payload;

import java.time.Instant;
import java.util.Map;

public record CommandPayload(
        String id,
        String type,
        Map<String, String> params,
        Instant createdAt
) {}
