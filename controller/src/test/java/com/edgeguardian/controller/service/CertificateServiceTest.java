package com.edgeguardian.controller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.model.*;
import com.edgeguardian.controller.repository.*;
import java.io.StringWriter;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

@Import({CertificateService.class, CertificateAuthorityService.class, CaKeyEncryption.class,
        AuditService.class, CrlService.class, CertificateServiceTest.MockEmqxConfig.class})
class CertificateServiceTest extends AbstractIntegrationTest {

    @Autowired private CertificateService certificateService;
    @Autowired private CertificateRequestRepository requestRepository;
    @Autowired private IssuedCertificateRepository certRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationCaRepository caRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;

    private Long orgId;
    private Long reviewerUserId;
    private static final String DEVICE_ID = "test-rpi-001";

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.builder()
                .name("Test Org").slug("cert-test-org").build());
        orgId = org.getId();

        reviewerUserId = userRepository.save(com.edgeguardian.controller.model.User.builder()
                .keycloakId("kc-cert-reviewer")
                .email("reviewer@cert.test")
                .displayName("Cert Reviewer")
                .build()).getId();

        deviceRepository.save(Device.builder()
                .deviceId(DEVICE_ID)
                .organizationId(orgId)
                .hostname("test-host")
                .architecture("arm64")
                .os("linux")
                .agentVersion("0.4.0")
                .labels(Map.of())
                .build());
    }

    @AfterEach
    void tearDown() {
        certRepository.deleteAll();
        requestRepository.deleteAll();
        auditLogRepository.deleteAll();
        caRepository.deleteAll();
        deviceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void initialRequest_pendingManualApproval() throws Exception {
        var result = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.INITIAL, null);

        assertThat(result.blocked()).isFalse();
        assertThat(result.certificate()).isNull();
        assertThat(result.request().getState()).isEqualTo(CertRequestState.PENDING);
    }

    @Test
    void manifestRequest_autoApproved() throws Exception {
        var result = certificateService.processRequest(
                DEVICE_ID, orgId, "grpc-server", "grpc.test.local",
                List.of("10.0.1.5"), generateTestCsr(), CertRequestType.MANIFEST, null);

        assertThat(result.blocked()).isFalse();
        assertThat(result.certificate()).isNotNull();
        assertThat(result.certificate().getCertPem()).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(result.certificate().getSerialNumber()).isNotBlank();
        assertThat(result.request().getState()).isEqualTo(CertRequestState.APPROVED);
    }

    @Test
    void approveAndReject() throws Exception {
        // Create pending request
        var result = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.INITIAL, null);

        Long requestId = result.request().getId();

        // Approve
        IssuedCertificate cert = certificateService.approve(requestId, reviewerUserId);
        assertThat(cert).isNotNull();
        assertThat(cert.getCertPem()).contains("BEGIN CERTIFICATE");
        assertThat(cert.getDeviceId()).isEqualTo(DEVICE_ID);

        // Verify request state updated
        CertificateRequest updated = requestRepository.findById(requestId).orElseThrow();
        assertThat(updated.getState()).isEqualTo(CertRequestState.APPROVED);
        assertThat(updated.getReviewedBy()).isEqualTo(reviewerUserId);
    }

    @Test
    void rejectRequest() throws Exception {
        var result = certificateService.processRequest(
                DEVICE_ID, orgId, "test-cert", "test.local",
                List.of(), generateTestCsr(), CertRequestType.INITIAL, null);

        certificateService.reject(result.request().getId(), reviewerUserId, "Not authorized");

        CertificateRequest updated = requestRepository.findById(result.request().getId()).orElseThrow();
        assertThat(updated.getState()).isEqualTo(CertRequestState.REJECTED);
        assertThat(updated.getRejectReason()).isEqualTo("Not authorized");
    }

    @Test
    void compromiseDetection_blocksAndRevokes() throws Exception {
        // First: issue a cert via manifest (auto-approved)
        var first = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.MANIFEST, null);
        assertThat(first.certificate()).isNotNull();

        // Second: try to get a NEW initial cert for the same name
        var second = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.INITIAL, null);

        // Should be blocked
        assertThat(second.blocked()).isTrue();
        assertThat(second.request().getState()).isEqualTo(CertRequestState.BLOCKED);

        // Original cert should be revoked
        IssuedCertificate revoked = certRepository.findById(first.certificate().getId()).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getRevokeReason()).isEqualTo(RevokeReason.COMPROMISED);

        // Device should be suspended
        Device device = deviceRepository.findByDeviceId(DEVICE_ID).orElseThrow();
        assertThat(device.getState()).isEqualTo(Device.DeviceState.SUSPENDED);
    }

    @Test
    void renewalRequest_autoApprovedWithValidSerial() throws Exception {
        // Issue initial cert
        var initial = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.MANIFEST, null);

        String serial = initial.certificate().getSerialNumber();

        // Request renewal with correct serial
        var renewal = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.RENEWAL, serial);

        assertThat(renewal.blocked()).isFalse();
        assertThat(renewal.certificate()).isNotNull();
        assertThat(renewal.certificate().getSerialNumber()).isNotEqualTo(serial);

        // Old cert should be revoked as RENEWED
        IssuedCertificate old = certRepository.findById(initial.certificate().getId()).orElseThrow();
        assertThat(old.isRevoked()).isTrue();
        assertThat(old.getRevokeReason()).isEqualTo(RevokeReason.RENEWED);
        assertThat(old.getReplacedBy()).isEqualTo(renewal.certificate().getId());
    }

    @Test
    void renewalRequest_rejectedWithInvalidSerial() throws Exception {
        var result = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.RENEWAL, "nonexistent-serial");

        assertThat(result.certificate()).isNull();
        assertThat(result.request().getState()).isEqualTo(CertRequestState.REJECTED);
    }

    @Test
    void renewalRequest_failsWithoutSerial() {
        assertThatThrownBy(() -> certificateService.processRequest(
                DEVICE_ID, orgId, "cert", "test.local",
                List.of(), generateTestCsr(), CertRequestType.RENEWAL, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void revokeCertificate() throws Exception {
        var result = certificateService.processRequest(
                DEVICE_ID, orgId, "test-cert", "test.local",
                List.of(), generateTestCsr(), CertRequestType.MANIFEST, null);

        certificateService.revoke(result.certificate().getId(), reviewerUserId);

        IssuedCertificate revoked = certRepository.findById(result.certificate().getId()).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getRevokeReason()).isEqualTo(RevokeReason.ADMIN_REVOKED);
    }

    @Test
    void revokeAlreadyRevokedThrows() throws Exception {
        var result = certificateService.processRequest(
                DEVICE_ID, orgId, "test-cert", "test.local",
                List.of(), generateTestCsr(), CertRequestType.MANIFEST, null);
        certificateService.revoke(result.certificate().getId(), reviewerUserId);

        assertThatThrownBy(() -> certificateService.revoke(result.certificate().getId(), reviewerUserId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @TestConfiguration
    static class MockEmqxConfig {
        @Bean
        EmqxAdminClient emqxAdminClient() {
            return Mockito.mock(EmqxAdminClient.class);
        }
    }

    // --- Helper: generate a real ECDSA CSR for testing ---

    private String generateTestCsr() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = keyGen.generateKeyPair();

        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=test.local");
        org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder csrBuilder =
                new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
        org.bouncycastle.operator.ContentSigner signer =
                new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());
        org.bouncycastle.pkcs.PKCS10CertificationRequest csr = csrBuilder.build(signer);

        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(csr);
        }
        return sw.toString();
    }
}
