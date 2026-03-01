package com.edgeguardian.controller.dto;

import java.time.Instant;
import java.util.List;

public record CreateApiKeyRequest(
        String name,
        List<String> scopes,
        Instant expiresAt
) {}
