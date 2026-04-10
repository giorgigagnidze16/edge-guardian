package com.edgeguardian.controller.security;

import com.edgeguardian.controller.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtTenantConverter implements Converter<Jwt, TenantAuthenticationToken> {

    private final UserService userService;

    @Override
    public TenantAuthenticationToken convert(Jwt jwt) {
        var user = userService.syncFromJwt(jwt);

        var principal = new TenantPrincipal(null, user.getId(), jwt.getSubject());
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new TenantAuthenticationToken(principal, jwt, authorities);
    }
}
