package com.edgeguardian.controller.security;

import com.edgeguardian.controller.model.ApiKey;
import com.edgeguardian.controller.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests via the X-API-Key header.
 * Looks up the key by SHA-256 hash in the database.
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyRepository apiKeyRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKeyValue = request.getHeader(API_KEY_HEADER);
        if (apiKeyValue != null && !apiKeyValue.isBlank()
            && SecurityContextHolder.getContext().getAuthentication() == null) {
            String hash = sha256(apiKeyValue);
            Optional<ApiKey> apiKey = apiKeyRepository.findByKeyHash(hash);
            if (apiKey.isPresent() && apiKey.get().isValid()) {
                ApiKey key = apiKey.get();
                var principal = new TenantPrincipal(key.getOrganizationId(), null, "apikey:" + key.getKeyPrefix());
                var auth = new TenantAuthenticationToken(
                    principal,
                    List.of(new SimpleGrantedAuthority("ROLE_API_KEY"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);

                // Update last used timestamp
                key.setLastUsedAt(java.time.Instant.now());
                apiKeyRepository.save(key);
            }
        }

        filterChain.doFilter(request, response);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
