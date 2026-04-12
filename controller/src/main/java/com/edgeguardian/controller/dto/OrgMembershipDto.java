package com.edgeguardian.controller.dto;

public record OrgMembershipDto(
        Long id,
        String name,
        String slug,
        String role
) {}
