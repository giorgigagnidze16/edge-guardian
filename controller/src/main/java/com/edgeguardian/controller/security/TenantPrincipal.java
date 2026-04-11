package com.edgeguardian.controller.security;

import com.edgeguardian.controller.model.OrgRole;

public record TenantPrincipal(
        Long organizationId,
        Long userId,
        String identity,
        OrgRole orgRole
) {
    public TenantPrincipal(Long organizationId, Long userId, String identity) {
        this(organizationId, userId, identity, null);
    }
}
