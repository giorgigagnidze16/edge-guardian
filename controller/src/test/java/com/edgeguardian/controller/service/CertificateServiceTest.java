package com.edgeguardian.controller.service;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.model.CertRequestState;
import com.edgeguardian.controller.model.CertRequestType;
import com.edgeguardian.controller.model.CertificateRequest;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.IssuedCertificate;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.RevokeReason;
import com.edgeguardian.controller.repository.AuditLogRepository;
import com.edgeguardian.controller.repository.CertificateRequestRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.IssuedCertificateRepository;
import com.edgeguardian.controller.repository.OrganizationCaRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import com.edgeguardian.controller.repository.UserRepository;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({CertificateService.class, CertificateAuthorityService.class, com.edgeguardian.controller.service.pki.OrganizationCaStore.class, CaKeyEncryption.class,
        AuditService.class, CrlService.class, CertificateServiceTest.MockEmqxConfig.class})
class CertificateServiceTest extends AbstractIntegrationTest {

    private static final String DEVICE_ID = "test-rpi-001";
    @Autowired
    private CertificateService certificateService;
    @Autowired
    private CertificateRequestRepository requestRepository;
    @Autowired
    private IssuedCertificateRepository certRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private OrganizationCaRepository caRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private UserRepository userRepository;
    private Long orgId;
    private Long reviewerUserId;

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
        IssuedCertificate cert = certificateService.approve(requestId, orgId, reviewerUserId);
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

        certificateService.reject(result.request().getId(), orgId, reviewerUserId, "Not authorized");

        CertificateRequest updated = requestRepository.findById(result.request().getId()).orElseThrow();
        assertThat(updated.getState()).isEqualTo(CertRequestState.REJECTED);
        assertThat(updated.getRejectReason()).isEqualTo("Not authorized");
    }

    @Test
    void reenrollment_supersedesPreviousCertWithoutBlocking() throws Exception {
        // First: issue a cert via manifest (auto-approved)
        var first = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.MANIFEST, null);
        assertThat(first.certificate()).isNotNull();

        // Second: legitimate re-enrollment for the same (deviceId, name) pair —
        // e.g. agent lost its identity dir after an OS reinstall. Expected
        // behaviour: old cert auto-revoked (SUPERSEDED), new cert issued,
        // device stays active.
        var second = certificateService.processRequest(
                DEVICE_ID, orgId, "device-identity", "test.local",
                List.of(), generateTestCsr(), CertRequestType.MANIFEST, null);

        assertThat(second.blocked()).isFalse();
        assertThat(second.certificate()).isNotNull();
        assertThat(second.certificate().getSerialNumber())
                .isNotEqualTo(first.certificate().getSerialNumber());

        IssuedCertificate oldCert = certRepository.findById(first.certificate().getId()).orElseThrow();
        assertThat(oldCert.isRevoked()).isTrue();
        assertThat(oldCert.getRevokeReason()).isEqualTo(RevokeReason.SUPERSEDED);

        Device device = deviceRepository.findByDeviceId(DEVICE_ID).orElseThrow();
        assertThat(device.getState()).isNotEqualTo(Device.DeviceState.SUSPENDED);
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

        certificateService.revoke(result.certificate().getId(), orgId, reviewerUserId);

        IssuedCertificate revoked = certRepository.findById(result.certificate().getId()).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getRevokeReason()).isEqualTo(RevokeReason.ADMIN_REVOKED);
    }

    @Test
    void revokeAlreadyRevokedThrows() throws Exception {
        var result = certificateService.processRequest(
                DEVICE_ID, orgId, "test-cert", "test.local",
                List.of(), generateTestCsr(), CertRequestType.MANIFEST, null);
        certificateService.revoke(result.certificate().getId(), orgId, reviewerUserId);

        assertThatThrownBy(() -> certificateService.revoke(result.certificate().getId(), orgId, reviewerUserId))
                .isInstanceOf(ResponseStatusException.class);
    }

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

    // --- Helper: generate a real ECDSA CSR for testing ---

    @TestConfiguration
    static class MockEmqxConfig {
        @Bean
        EmqxAdminClient emqxAdminClient() {
            return Mockito.mock(EmqxAdminClient.class);
        }
    }
}
