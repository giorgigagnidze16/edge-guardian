package com.edgeguardian.controller.dto;

/**
 * Result of an invite. Exactly one of {@code member} (email belonged to a
 * registered user, added now) or {@code invitation} (pending until first login)
 * is set; {@code status} is {@code "added"} or {@code "invited"}.
 */
public record InviteResultDto(
        String status,
        MemberDto member,
        InvitationDto invitation
) {
    public static InviteResultDto added(MemberDto member) {
        return new InviteResultDto("added", member, null);
    }

    public static InviteResultDto invited(InvitationDto invitation) {
        return new InviteResultDto("invited", null, invitation);
    }
}
