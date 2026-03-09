package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.model.User;

import java.time.Instant;

public record MemberDto(
        Long id,
        Long userId,
        String email,
        String displayName,
        String avatarUrl,
        String role,
        Instant joinedAt
) {
    public static MemberDto from(OrganizationMember member, User user) {
        return new MemberDto(
                member.getId(),
                member.getUserId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                member.getOrgRole().name(),
                member.getCreatedAt()
        );
    }
}
