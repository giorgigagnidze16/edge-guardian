package com.edgeguardian.controller.dto;

public record UpdateOrganizationRequest(
        String name,
        String description
) {}
