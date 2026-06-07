package com.edgeguardian.controller.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A pending invitation for an email that is not yet a registered user. It is
 * consumed into an {@link OrganizationMember} when that email first logs in, or
 * removed when an admin revokes it. OWNER is never stored here.
 */
@Entity
@Table(name = "organization_invitations",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "email"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationInvitation extends AbstractCreatedEntity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "org_role", nullable = false)
    @Builder.Default
    private OrgRole orgRole = OrgRole.VIEWER;

    @Column(name = "invited_by")
    private Long invitedByUserId;
}
