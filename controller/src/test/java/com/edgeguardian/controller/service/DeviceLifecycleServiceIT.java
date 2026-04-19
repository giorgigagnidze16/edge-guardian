package com.edgeguardian.controller.service;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.model.AuditLog;
import com.edgeguardian.controller.model.CertRequestState;
import com.edgeguardian.controller.model.CertRequestType;
import com.edgeguardian.controller.model.CertificateRequest;
import com.edgeguardian.controller.model.DeviceToken;
import com.edgeguardian.controller.model.IssuedCertificate;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.RevokeReason;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.AuditLogRepository;
import com.edgeguardian.controller.repository.CertificateRequestRepository;
import com.edgeguardian.controller.repository.DeviceManifestRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.DeviceTokenRepository;
import com.edgeguardian.controller.repository.IssuedCertificateRepository;
import com.edgeguardian.controller.repository.OrganizationCaRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import com.edgeguardian.controller.repository.UserRepository;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for the device-delete cascade.
 * Uses the real PostgreSQL (Testcontainers) to verify that deleting a device actually
 * revokes its certs, rejects its pending requests, removes its token and manifest,
 * and writes an audit entry - all in one transaction.
 */
@Import({DeviceLifecycleService.class, DeviceRegistry.class, CertificateService.class,
        CertificateAuthorityService.class, com.edgeguardian.controller.service.pki.OrganizationCaStore.class, CaKeyEncryption.class, AuditService.class,
        CrlService.class, DeviceLifecycleServiceIT.MockEmqxConfig.class})
class DeviceLifecycleServiceIT extends AbstractIntegrationTest {

    private static final String DEVICE_ID = "rpi-lifecycle-it";
    private static final String OTHER_DEVICE_ID = "rpi-lifecycle-it-other";

    @Autowired
    private DeviceLifecycleService lifecycle;
    @Autowired
    private CertificateService certificateService;
    @Autowired
    private DeviceRegistry registry;

    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DeviceManifestRepository manifestRepository;
    @Autowired
    private DeviceTokenRepository deviceTokenRepository;
    @Autowired
    private CertificateRequestRepository requestRepository;
    @Autowired
    private IssuedCertificateRepository certRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private OrganizationCaRepository caRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private UserRepository userRepository;

    private Long orgId;
    private Long actorUserId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.builder()
                .name("Lifecycle Org").slug("lifecycle-it-org").build());
        orgId = org.getId();

        User actor = userRepository.save(User.builder()
                .keycloakId("kc-lifecycle-actor")
                .email("actor@lifecycle.test")
                .displayName("Lifecycle Actor")
                .build());
        actorUserId = actor.getId();

        registry.register(orgId, DEVICE_ID, "host", "arm64", "linux", "0.4.0");
        registry.saveManifest(DEVICE_ID, Map.of("name", DEVICE_ID), Map.of());
    }

    @AfterEach
    void tearDown() {
        certRepository.deleteAll();
        requestRepository.deleteAll();
        deviceTokenRepository.deleteAll();
        manifestRepository.deleteAll();
        auditLogRepository.deleteAll();
        caRepository.deleteAll();
        deviceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void deleteDevice_revokesCertsRejectsPendingRemovesToken() throws Exception {
        // Issue one auto-approved cert via manifest
        var manifestResult = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "dev.local",
                List.of(), generateCsr(), CertRequestType.MANIFEST, null);
        assertThat(manifestResult.certificate()).isNotNull();
        Long certId = manifestResult.certificate().getId();

        // Seed a pending request for the same device (different cert name, so no compromise block)
        var pendingResult = certificateService.processRequest(
                DEVICE_ID, orgId, "grpc-client", "grpc.dev.local",
                List.of(), generateCsr(), CertRequestType.INITIAL, null);
        assertThat(pendingResult.request().getState()).isEqualTo(CertRequestState.PENDING);
        Long pendingRequestId = pendingResult.request().getId();

        // Seed a device token
        deviceTokenRepository.save(DeviceToken.builder()
                .deviceId(DEVICE_ID)
                .tokenHash("hash-" + DEVICE_ID)
                .tokenPrefix("eg_")
                .issuedAt(Instant.now())
                .build());
        assertThat(deviceTokenRepository.findByDeviceId(DEVICE_ID)).isPresent();

        // Act
        lifecycle.deleteDevice(DEVICE_ID, actorUserId);

        // Device + manifest gone
        assertThat(deviceRepository.findByDeviceId(DEVICE_ID)).isEmpty();
        assertThat(manifestRepository.findByDeviceId(DEVICE_ID)).isEmpty();

        // Token deleted - agent endpoint auth will reject it going forward
        assertThat(deviceTokenRepository.findByDeviceId(DEVICE_ID)).isEmpty();

        // Cert is revoked with DEVICE_DELETED reason (rows are retained for audit/CRL)
        IssuedCertificate cert = certRepository.findById(certId).orElseThrow();
        assertThat(cert.isRevoked()).isTrue();
        assertThat(cert.getRevokeReason()).isEqualTo(RevokeReason.DEVICE_DELETED);
        assertThat(cert.getRevokedAt()).isNotNull();

        // Pending request moved to REJECTED
        CertificateRequest pending = requestRepository.findById(pendingRequestId).orElseThrow();
        assertThat(pending.getState()).isEqualTo(CertRequestState.REJECTED);
        assertThat(pending.getRejectReason()).isEqualTo("Device deleted");
        assertThat(pending.getReviewedBy()).isEqualTo(actorUserId);
        assertThat(pending.getReviewedAt()).isNotNull();

        // Audit entry written
        List<AuditLog> logs = auditLogRepository.findAll();
        assertThat(logs).anyMatch(l ->
                "device_deleted".equals(l.getAction())
                        && DEVICE_ID.equals(l.getResourceId())
                        && actorUserId.equals(l.getUserId())
                        && orgId.equals(l.getOrganizationId()));
    }

    @Test
    void deleteDevice_doesNotTouchOtherDevicesCerts() throws Exception {
        registry.register(orgId, OTHER_DEVICE_ID, "host2", "arm64", "linux", "0.4.0");
        var victim = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "dev.local",
                List.of(), generateCsr(), CertRequestType.MANIFEST, null);
        var survivor = certificateService.processRequest(
                OTHER_DEVICE_ID, orgId, "device-identity", "other.local",
                List.of(), generateCsr(), CertRequestType.MANIFEST, null);

        lifecycle.deleteDevice(DEVICE_ID, actorUserId);

        IssuedCertificate survivorCert = certRepository.findById(survivor.certificate().getId())
                .orElseThrow();
        assertThat(survivorCert.isRevoked()).isFalse();
        assertThat(deviceRepository.findByDeviceId(OTHER_DEVICE_ID)).isPresent();

        // Victim cert is revoked
        IssuedCertificate victimCert = certRepository.findById(victim.certificate().getId())
                .orElseThrow();
        assertThat(victimCert.isRevoked()).isTrue();
    }

    @Test
    void deleteDevice_missingDevice_throws404() {
        assertThatThrownBy(() -> lifecycle.deleteDevice("ghost-device", actorUserId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deleteDevice_isIdempotentWhenNoCertsOrTokens() {
        // Fresh device, no certs, no tokens - delete should still succeed and audit.
        lifecycle.deleteDevice(DEVICE_ID, actorUserId);

        assertThat(deviceRepository.findByDeviceId(DEVICE_ID)).isEmpty();
        assertThat(auditLogRepository.findAll())
                .anyMatch(l -> "device_deleted".equals(l.getAction())
                        && DEVICE_ID.equals(l.getResourceId()));
    }

    private String generateCsr() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = keyGen.generateKeyPair();

        var subject = new X500Name("CN=test.local");
        var csrBuilder = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());
        var csr = csrBuilder.build(signer);

        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(csr);
        }
        return sw.toString();
    }

    @TestConfiguration
    static class MockEmqxConfig {
        @Bean
        EmqxAdminClient emqxAdminClient() {
            return Mockito.mock(EmqxAdminClient.class);
        }
    }
}
