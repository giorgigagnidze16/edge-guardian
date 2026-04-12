package com.edgeguardian.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.model.*;
import com.edgeguardian.controller.repository.*;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/**
 * End-to-end test of the CSR-in-enrollment flow. We don't exercise the full MQTT listener
 * (that requires a broker), but we validate the service-layer composition that the listener
 * performs: EnrollmentService creates the device, CertificateService signs the CSR as a
 * MANIFEST request, and the resulting IssuedCertificate carries a valid serial + PEM.
 */
@Import({EnrollmentService.class, DeviceRegistry.class, CertificateService.class,
        CertificateAuthorityService.class, CaKeyEncryption.class, AuditService.class,
        CrlService.class, EnrollmentWithCsrIT.MockEmqxConfig.class})
class EnrollmentWithCsrIT extends AbstractIntegrationTest {

    @Autowired private EnrollmentService enrollmentService;
    @Autowired private CertificateService certificateService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private EnrollmentTokenRepository enrollmentTokenRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private IssuedCertificateRepository certRepository;
    @Autowired private CertificateRequestRepository requestRepository;
    @Autowired private OrganizationCaRepository caRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private Long orgId;
    private String enrollmentToken;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.builder()
                .name("Enroll Test Org").slug("enroll-csr-it-org").build());
        orgId = org.getId();

        enrollmentToken = "egt_test_" + System.nanoTime();
        enrollmentTokenRepository.save(EnrollmentToken.builder()
                .organizationId(orgId)
                .token(enrollmentToken)
                .name("e2e-test-token")
                .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .maxUses(10)
                .build());
    }

    @AfterEach
    void tearDown() {
        certRepository.deleteAll();
        requestRepository.deleteAll();
        auditLogRepository.deleteAll();
        caRepository.deleteAll();
        deviceRepository.deleteAll();
        enrollmentTokenRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void enrollAndSignCsr_producesValidIdentityCert() throws Exception {
        // Enroll — registers the device and increments token useCount.
        var result = enrollmentService.enrollDevice(
                enrollmentToken, "rpi-001", "host", "arm64", "linux", "0.4.0", Map.of());

        assertThat(result.device()).isNotNull();
        assertThat(result.deviceToken()).startsWith("edt_");

        // Now the same flow that EnrollmentListener runs inline: sign the CSR.
        String csr = generateCsr("rpi-001");
        var certResult = certificateService.processRequest(
                "rpi-001", orgId, "device-identity", "rpi-001",
                List.of(), csr, CertRequestType.MANIFEST, null);

        assertThat(certResult.blocked()).isFalse();
        assertThat(certResult.certificate()).isNotNull();
        assertThat(certResult.certificate().getCertPem()).contains("BEGIN CERTIFICATE");
        assertThat(certResult.certificate().getSerialNumber()).isNotBlank();
        assertThat(certResult.certificate().getName()).isEqualTo("device-identity");
        assertThat(certResult.certificate().getDeviceId()).isEqualTo("rpi-001");
    }

    @Test
    void enrollTwiceWithCsr_secondTimeBlocksAsCompromised() throws Exception {
        enrollmentService.enrollDevice(enrollmentToken, "rpi-001", "host", "arm64", "linux", "0.4.0", Map.of());
        var first = certificateService.processRequest(
                "rpi-001", orgId, "device-identity", "rpi-001",
                List.of(), generateCsr("rpi-001"), CertRequestType.MANIFEST, null);
        assertThat(first.certificate()).isNotNull();

        // Second identity cert request for the same device while the first is still valid
        // must trip compromise detection — the controller has no way to know whether the
        // original key was exfiltrated or the device simply lost state.
        var second = certificateService.processRequest(
                "rpi-001", orgId, "device-identity", "rpi-001",
                List.of(), generateCsr("rpi-001"), CertRequestType.MANIFEST, null);

        assertThat(second.blocked()).isTrue();
        assertThat(second.certificate()).isNull();

        // Device is suspended and the old cert is revoked.
        Device suspended = deviceRepository.findByDeviceId("rpi-001").orElseThrow();
        assertThat(suspended.getState()).isEqualTo(Device.DeviceState.SUSPENDED);

        IssuedCertificate oldCert = certRepository.findById(first.certificate().getId()).orElseThrow();
        assertThat(oldCert.isRevoked()).isTrue();
        assertThat(oldCert.getRevokeReason()).isEqualTo(RevokeReason.COMPROMISED);
    }

    private String generateCsr(String cn) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = keyGen.generateKeyPair();
        var builder = new JcaPKCS10CertificationRequestBuilder(
                new X500Name("CN=" + cn), kp.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate());
        var csr = builder.build(signer);
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) { w.writeObject(csr); }
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
