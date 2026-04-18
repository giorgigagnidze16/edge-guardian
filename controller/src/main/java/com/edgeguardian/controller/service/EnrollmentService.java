package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceToken;
import com.edgeguardian.controller.model.EnrollmentToken;
import com.edgeguardian.controller.repository.DeviceTokenRepository;
import com.edgeguardian.controller.repository.EnrollmentTokenRepository;
import com.edgeguardian.controller.security.TokenHasher;
import com.edgeguardian.controller.service.result.EnrollmentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EnrollmentTokenRepository tokenRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final DeviceRegistry deviceRegistry;

    @Transactional
    public EnrollmentToken createToken(Long orgId, String name, Instant expiresAt,
                                       Integer maxUses, Long createdBy) {
        String token = generateSecureToken();
        EnrollmentToken enrollmentToken = EnrollmentToken.builder()
                .organizationId(orgId)
                .token(token)
                .name(name)
                .expiresAt(expiresAt)
                .maxUses(maxUses)
                .createdBy(createdBy)
                .build();
        return tokenRepository.save(enrollmentToken);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentToken> findByOrganization(Long orgId) {
        return tokenRepository.findByOrganizationIdAndRevokedFalse(orgId);
    }

    @Transactional
    public void revokeToken(Long tokenId, Long expectedOrgId) {
        EnrollmentToken token = tokenRepository.findById(tokenId)
                .filter(t -> expectedOrgId.equals(t.getOrganizationId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found"));
        token.setRevoked(true);
        tokenRepository.save(token);
    }

    /**
     * Enroll a device using an enrollment token.
     * Returns the device record (bound to the token's org) and a one-time device token.
     */
    @Transactional
    public EnrollmentResult enrollDevice(String tokenValue, String deviceId, String hostname,
                                         String architecture, String os, String agentVersion,
                                         Map<String, String> labels) {
        EnrollmentToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid enrollment token"));

        if (!token.isValid()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Enrollment token expired or revoked");
        }

        // Increment use count
        token.setUseCount(token.getUseCount() + 1);
        tokenRepository.save(token);

        // Register device with org binding
        Device device = deviceRegistry.register(token.getOrganizationId(), deviceId, hostname, architecture, os, agentVersion, labels);

        // Generate new device token (update existing on re-enrollment)
        String rawToken = generateDeviceToken();
        String tokenHash = TokenHasher.sha256(rawToken);
        String prefix = rawToken.substring(0, Math.min(12, rawToken.length()));

        DeviceToken deviceToken = deviceTokenRepository.findByDeviceId(deviceId)
                .orElse(DeviceToken.builder().deviceId(deviceId).build());
        deviceToken.setTokenHash(tokenHash);
        deviceToken.setTokenPrefix(prefix);
        deviceToken.setRevoked(false);
        deviceTokenRepository.save(deviceToken);

        log.info("Device {} enrolled to org {} via token {}",
                deviceId, token.getOrganizationId(), token.getName());
        return new EnrollmentResult(device, rawToken);
    }

    private String generateDeviceToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "edt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional(readOnly = true)
    public Optional<EnrollmentToken> findById(Long id) {
        return tokenRepository.findById(id);
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "egt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
