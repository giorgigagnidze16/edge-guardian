package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.ApiKey;
import com.edgeguardian.controller.repository.ApiKeyRepository;
import com.edgeguardian.controller.security.TokenHasher;
import com.edgeguardian.controller.service.result.ApiKeyCreateResult;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;

    /**
     * Create a new API key. Returns the raw key value (only shown once).
     */
    @Transactional
    public ApiKeyCreateResult create(Long orgId, String name, List<String> scopes,
                                     Instant expiresAt, Long createdBy) {
        String rawKey = generateRawKey();
        String prefix = rawKey.substring(0, 8);
        String hash = TokenHasher.sha256(rawKey);

        ApiKey apiKey = ApiKey.builder()
                .organizationId(orgId)
                .keyHash(hash)
                .keyPrefix(prefix)
                .name(name)
                .scopes(scopes != null ? scopes : List.of())
                .expiresAt(expiresAt)
                .createdBy(createdBy)
                .build();
        apiKey = apiKeyRepository.save(apiKey);

        return new ApiKeyCreateResult(apiKey, rawKey);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findByOrganization(Long orgId) {
        return apiKeyRepository.findByOrganizationId(orgId);
    }

    @Transactional
    public void revoke(Long keyId, Long expectedOrgId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .filter(k -> expectedOrgId.equals(k.getOrganizationId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
        key.setRevoked(true);
        apiKeyRepository.save(key);
    }

    private String generateRawKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "egk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
