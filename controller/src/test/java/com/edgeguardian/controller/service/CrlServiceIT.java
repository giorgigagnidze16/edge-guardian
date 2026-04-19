package com.edgeguardian.controller.service;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.model.CertRequestType;
import com.edgeguardian.controller.model.CertificateRevocationList;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.model.User;
import com.edgeguardian.controller.repository.AuditLogRepository;
import com.edgeguardian.controller.repository.CertificateRequestRepository;
import com.edgeguardian.controller.repository.CertificateRevocationListRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
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

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that CRL generation actually works end-to-end: after a revocation,
 * the CRL rebuild is signed by the org CA and the revoked cert's serial appears
 * in the parsed CRL when validated against the CA.
 */
@Import({CrlService.class, CertificateService.class, CertificateAuthorityService.class, com.edgeguardian.controller.service.pki.OrganizationCaStore.class,
        CaKeyEncryption.class, AuditService.class, CrlServiceIT.MockEmqxConfig.class})
class CrlServiceIT extends AbstractIntegrationTest {

    @Autowired
    private CrlService crlService;
    @Autowired
    private CertificateService certificateService;
    @Autowired
    private CertificateAuthorityService caService;
    @Autowired
    private CertificateRevocationListRepository crlRepository;
    @Autowired
    private IssuedCertificateRepository certRepository;
    @Autowired
    private CertificateRequestRepository requestRepository;
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
    @Autowired
    private EmqxAdminClient emqxAdminClient;

    private Long orgId;
    private Long reviewerId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.builder()
                .name("CRL Test Org").slug("crl-test-org").build());
        orgId = org.getId();

        reviewerId = userRepository.save(User.builder()
                .keycloakId("kc-crl-reviewer")
                .email("crl@reviewer.test")
                .displayName("CRL Reviewer")
                .build()).getId();

        deviceRepository.save(Device.builder()
                .deviceId("dev-crl")
                .organizationId(orgId)
                .hostname("h").architecture("arm64").os("linux").agentVersion("0.4.0")
                .labels(Map.of()).build());
    }

    @AfterEach
    void tearDown() {
        crlRepository.deleteAll();
        certRepository.deleteAll();
        requestRepository.deleteAll();
        auditLogRepository.deleteAll();
        caRepository.deleteAll();
        deviceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void rebuild_emptyState_producesValidSignedCrlWithNoEntries() throws Exception {
        CertificateRevocationList crl = crlService.rebuild(orgId);

        assertThat(crl.getCrlNumber()).isEqualTo(1L);
        assertThat(crl.getRevokedCount()).isZero();

        X509CRL parsed = parseCrl(crl.getCrlDer());
        parsed.verify(loadCaPublicKey(orgId));
        assertThat(parsed.getRevokedCertificates()).isNullOrEmpty();
    }

    @Test
    void revoke_endsUpInCrl_signedByOrgCa() throws Exception {
        var result = certificateService.processRequest(
                "dev-crl", orgId, "device-identity", "dev.local",
                List.of(), generateCsr(), CertRequestType.MANIFEST, null);

        String hexSerial = result.certificate().getSerialNumber();
        BigInteger expectedSerial = new BigInteger(hexSerial, 16);

        certificateService.revoke(result.certificate().getId(), orgId, reviewerId);

        CertificateRevocationList crl = crlRepository.findByOrganizationId(orgId).orElseThrow();
        X509CRL parsed = parseCrl(crl.getCrlDer());

        // Signature must validate against the org CA public key - this is the
        // property every TLS verifier relies on.
        parsed.verify(loadCaPublicKey(orgId));

        X509CRLEntry entry = parsed.getRevokedCertificate(expectedSerial);
        assertThat(entry).as("revoked serial must appear in CRL").isNotNull();
        assertThat(crl.getRevokedCount()).isEqualTo(1);

        // Kickout must be invoked so active sessions drop - the CRL only helps next handshake.
        Mockito.verify(emqxAdminClient).kickout("dev-crl");
    }

    @Test
    void crlNumber_monotonicallyIncreases() {
        crlService.rebuild(orgId);
        crlService.rebuild(orgId);
        CertificateRevocationList third = crlService.rebuild(orgId);
        assertThat(third.getCrlNumber()).isEqualTo(3L);
    }

    private X509CRL parseCrl(byte[] der) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509CRL) cf.generateCRL(new ByteArrayInputStream(der));
    }

    private java.security.PublicKey loadCaPublicKey(Long orgId) throws Exception {
        String caPem = caService.getCaCertPem(orgId);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return cf.generateCertificate(new ByteArrayInputStream(
                caPem.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getPublicKey();
    }

    private String generateCsr() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = keyGen.generateKeyPair();
        var builder = new JcaPKCS10CertificationRequestBuilder(
                new X500Name("CN=dev.local"), keyPair.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());
        var csr = builder.build(signer);
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(csr);
        }
        return sw.toString();
    }

    /**
     * Stand-in for {@link EmqxAdminClient} - we don't want real HTTP traffic in tests,
     * but we do want to assert kickout is invoked on revoke.
     */
    @TestConfiguration
    static class MockEmqxConfig {
        @Bean
        EmqxAdminClient emqxAdminClient() {
            return Mockito.mock(EmqxAdminClient.class);
        }
    }
}
