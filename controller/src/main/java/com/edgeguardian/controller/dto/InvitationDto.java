package com.edgeguardian.controller.dto;

import com.edgeguardian.controller.model.OrganizationInvitation;

import java.time.Instant;

public record InvitationDto(
        Long id,
        String email,
        String role,
        Instant createdAt
) {
    public static InvitationDto from(OrganizationInvitation invitation) {
        return new InvitationDto(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getOrgRole().name(),
                invitation.getCreatedAt()
        );
    }
}
