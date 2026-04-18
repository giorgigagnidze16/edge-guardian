package com.edgeguardian.controller.security;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceToken;
import com.edgeguardian.controller.repository.DeviceTokenRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Authenticates agent requests via the X-Device-Token header (tokens issued during enrollment).
 * Uses a custom header to avoid conflicts with the JWT BearerTokenAuthenticationFilter.
 */
@Component
@RequiredArgsConstructor
public class DeviceTokenAuthFilter extends OncePerRequestFilter {

    private static final String DEVICE_TOKEN_HEADER = "X-Device-Token";
    private static final Set<String> PUBLIC_AGENT_PATHS = Set.of(
            "/api/v1/agent/enroll",
            "/api/v1/agent/installer",
            "/api/v1/agent/binary"
    );

    private final DeviceTokenRepository deviceTokenRepository;
    private final DeviceRepository deviceRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!path.startsWith("/api/v1/agent/")) {
            return true;
        }
        return PUBLIC_AGENT_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = request.getHeader(DEVICE_TOKEN_HEADER);
        if (rawToken == null || rawToken.isBlank()) {
            writeError(response, "Missing X-Device-Token header");
            return;
        }

        DeviceToken deviceToken = deviceTokenRepository
                .findByTokenHash(TokenHasher.sha256(rawToken))
                .filter(t -> !t.isRevoked())
                .orElse(null);

        if (deviceToken == null) {
            writeError(response, "Invalid or revoked device token");
            return;
        }

        Long orgId = deviceRepository.findByDeviceId(deviceToken.getDeviceId())
                .map(Device::getOrganizationId)
                .orElse(null);

        var principal = new TenantPrincipal(orgId, null, "device:" + deviceToken.getDeviceId());
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        deviceToken.setLastUsedAt(Instant.now());
        deviceTokenRepository.save(deviceToken);

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
