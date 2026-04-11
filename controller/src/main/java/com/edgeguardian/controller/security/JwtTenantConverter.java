package com.edgeguardian.controller.security;

import com.edgeguardian.controller.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtTenantConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserService userService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var user = userService.syncFromJwt(jwt);
        var principal = new TenantPrincipal(null, user.getId(), jwt.getSubject());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
