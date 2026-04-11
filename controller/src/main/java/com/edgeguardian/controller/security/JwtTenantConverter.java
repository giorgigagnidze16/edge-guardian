package com.edgeguardian.controller.security;

import com.edgeguardian.controller.model.OrganizationMember;
import com.edgeguardian.controller.repository.OrganizationMemberRepository;
import com.edgeguardian.controller.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtTenantConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserService userService;
    private final OrganizationMemberRepository memberRepository;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var user = userService.syncFromJwt(jwt);

        // Load the user's single org membership
        var membership = memberRepository.findByUserId(user.getId()).stream().findFirst().orElse(null);

        var principal = new TenantPrincipal(
                membership != null ? membership.getOrganizationId() : null,
                user.getId(),
                jwt.getSubject(),
                membership != null ? membership.getOrgRole() : null);

        return UsernamePasswordAuthenticationToken.authenticated(
                principal, jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
