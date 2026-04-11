package com.edgeguardian.controller.security;

import com.edgeguardian.controller.model.OrgRole;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("orgSecurity")
public class OrgSecurity {

    public boolean hasMinRole(Authentication authentication, String minRole) {
        TenantPrincipal principal = extractPrincipal(authentication);
        if (principal == null || principal.orgRole() == null) return false;
        return roleLevel(principal.orgRole()) >= roleLevel(OrgRole.valueOf(minRole));
    }

    private TenantPrincipal extractPrincipal(Authentication authentication) {
        if (authentication == null) return null;
        return authentication.getPrincipal() instanceof TenantPrincipal tp ? tp : null;
    }

    private int roleLevel(OrgRole role) {
        return switch (role) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case OPERATOR -> 2;
            case VIEWER -> 1;
        };
    }
}
