package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.EnrollmentToken;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.EnrollmentTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Service
public class EnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EnrollmentTokenRepository tokenRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceRegistry deviceRegistry;

    public EnrollmentService(EnrollmentTokenRepository tokenRepository,
                             DeviceRepository deviceRepository,
                             DeviceRegistry deviceRegistry) {
        this.tokenRepository = tokenRepository;
        this.deviceRepository = deviceRepository;
        this.deviceRegistry = deviceRegistry;
    }

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
        return tokenRepository.findByOrganizationId(orgId);
    }

    @Transactional
    public void revokeToken(Long tokenId) {
        EnrollmentToken token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found"));
        token.setRevoked(true);
        tokenRepository.save(token);
    }

    /**
     * Enroll a device using an enrollment token.
     * Returns the device record (now bound to the token's org).
     */
    @Transactional
    public Device enrollDevice(String tokenValue, String deviceId, String hostname,
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
        Device device = deviceRegistry.register(deviceId, hostname, architecture, os, agentVersion, labels);
        device.setOrganizationId(token.getOrganizationId());
        deviceRepository.save(device);

        log.info("Device {} enrolled to org {} via token {}",
                deviceId, token.getOrganizationId(), token.getName());
        return device;
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
