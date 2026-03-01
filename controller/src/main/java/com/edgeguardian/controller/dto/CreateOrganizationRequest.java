package com.edgeguardian.controller.dto;

public record CreateOrganizationRequest(
        String name,
        String slug,
        String description
) {}
