package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.OrganizationMember;

import java.time.Instant;

public record MemberDto(
        Long id,
        Long userId,
        String role,
        Instant createdAt
) {
    public static MemberDto from(OrganizationMember member) {
        return new MemberDto(
                member.getId(),
                member.getUserId(),
                member.getOrgRole().name(),
                member.getCreatedAt()
        );
    }
}
