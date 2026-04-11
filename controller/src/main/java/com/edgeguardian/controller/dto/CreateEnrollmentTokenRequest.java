package com.edgeguardian.controller.dto;

import java.time.Instant;

public record CreateEnrollmentTokenRequest(
        String name,
        String description,
        Instant expiresAt,
        Integer maxUses
) {
    /**
     * Returns name if set, otherwise falls back to description (UI sends description).
     */
    public String resolvedName() {
        if (name != null && !name.isBlank()) return name;
        if (description != null && !description.isBlank()) return description;
        return "Untitled";
    }
}
