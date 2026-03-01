package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.Organization;

import java.time.Instant;

public record OrganizationDto(
        Long id,
        String name,
        String slug,
        String description,
        Instant createdAt
) {
    public static OrganizationDto from(Organization org) {
        return new OrganizationDto(
                org.getId(),
                org.getName(),
                org.getSlug(),
                org.getDescription(),
                org.getCreatedAt()
        );
    }
}
