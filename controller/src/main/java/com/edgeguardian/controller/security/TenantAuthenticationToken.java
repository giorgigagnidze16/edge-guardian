package com.edgeguardian.controller.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class TenantAuthenticationToken extends AbstractAuthenticationToken {

    private final TenantPrincipal principal;
    private final Jwt jwt;

    public TenantAuthenticationToken(TenantPrincipal principal,
                                     Collection<? extends GrantedAuthority> authorities) {
        this(principal, null, authorities);
    }

    public TenantAuthenticationToken(TenantPrincipal principal, Jwt jwt,
                                     Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public TenantPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    public Jwt getJwt() {
        return jwt;
    }
}
