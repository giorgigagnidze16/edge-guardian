package com.edgeguardian.controller.dto;

public record ApiKeyCreateResponse(
        ApiKeyDto apiKey,
        String rawKey
) {}
