package com.edgeguardian.controller.dto;

import java.time.Instant;

public record CreateEnrollmentTokenRequest(
        String name,
        Instant expiresAt,
        Integer maxUses
) {}
