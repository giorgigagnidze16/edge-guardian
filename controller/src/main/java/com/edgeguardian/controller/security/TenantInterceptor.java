package com.edgeguardian.controller.security;

import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Extracts tenant info from the JWT and populates TenantContext.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    public TenantInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String keycloakId = jwt.getSubject();
            Optional<User> user = userRepository.findByKeycloakId(keycloakId);
            user.ifPresent(u -> TenantContext.setUserId(u.getId()));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
