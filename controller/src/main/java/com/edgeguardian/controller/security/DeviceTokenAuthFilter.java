package com.edgeguardian.controller.security;

import com.edgeguardian.controller.model.DeviceToken;
import com.edgeguardian.controller.repository.DeviceTokenRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates agent requests via the X-Device-Token header (tokens issued during enrollment).
 * Uses a custom header to avoid conflicts with the JWT BearerTokenAuthenticationFilter.
 */
@Component
@RequiredArgsConstructor
public class DeviceTokenAuthFilter extends OncePerRequestFilter {

    private static final String DEVICE_TOKEN_HEADER = "X-Device-Token";

    private final DeviceTokenRepository deviceTokenRepository;
    private final DeviceRepository deviceRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Only filter agent API paths (except /enroll which is unauthenticated)
        if (!path.startsWith("/api/v1/agent/")) {
            return true;
        }
        return path.equals("/api/v1/agent/enroll");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Skip if already authenticated (e.g., by API key filter)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = request.getHeader(DEVICE_TOKEN_HEADER);
        if (rawToken == null || rawToken.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing X-Device-Token header\"}");
            return;
        }
        String tokenHash = ApiKeyAuthenticationFilter.sha256(rawToken);

        Optional<DeviceToken> tokenOpt = deviceTokenRepository.findByTokenHash(tokenHash);
        if (tokenOpt.isEmpty() || tokenOpt.get().isRevoked()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or revoked device token\"}");
            return;
        }

        DeviceToken deviceToken = tokenOpt.get();

        Long orgId = deviceRepository.findByDeviceId(deviceToken.getDeviceId())
                .map(device -> device.getOrganizationId())
                .orElse(null);

        var principal = new TenantPrincipal(orgId, null, "device:" + deviceToken.getDeviceId());
        var auth = new TenantAuthenticationToken(
                principal,
                List.of(new SimpleGrantedAuthority("ROLE_DEVICE"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Update last used timestamp
        deviceToken.setLastUsedAt(Instant.now());
        deviceTokenRepository.save(deviceToken);

        filterChain.doFilter(request, response);
    }
}
