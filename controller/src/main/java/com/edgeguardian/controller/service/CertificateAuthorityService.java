package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.CaProperties;
import com.edgeguardian.controller.config.PkiProperties;
import com.edgeguardian.controller.model.OrganizationCa;
import com.edgeguardian.controller.repository.OrganizationCaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-organization Certificate Authority.
 * Generates ECDSA P-256 CAs on first use, signs device CSRs.
 * CA private keys are AES-256-GCM encrypted at rest via {@link CaKeyEncryption}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateAuthorityService {

    private static final int SERIAL_BITS = 160;
    private static final String CURVE = "secp256r1";
    private static final String KEY_ALGORITHM = "EC";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CaProperties caProperties;
    private final PkiProperties pkiProperties;
    private final CaKeyEncryption keyEncryption;
    private final OrganizationCaRepository caRepository;

    private final Map<Long, String> caCertCache = new ConcurrentHashMap<>();

    private static BigInteger newSerial() {
        return new BigInteger(SERIAL_BITS, SECURE_RANDOM);
    }

    private static X509Certificate parseCertPem(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            return new JcaX509CertificateConverter().getCertificate((X509CertificateHolder) parser.readObject());
        } catch (IOException | CertificateException e) {
            throw new IllegalArgumentException("Invalid certificate PEM", e);
        }
    }

    private static PKCS10CertificationRequest parseCsrPem(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            return (PKCS10CertificationRequest) parser.readObject();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid CSR PEM", e);
        }
    }

    private static String toPem(Object obj) {
        try {
            StringWriter sw = new StringWriter();
            try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
                writer.writeObject(obj);
            }
            return sw.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode PEM", e);
        }
    }

    public String getCaCertPem(Long orgId) {
        String cached = caCertCache.get(orgId);
        if (cached != null) return cached;

        String certPem = getOrCreateCa(orgId).getCaCertPem();
        caCertCache.put(orgId, certPem);
        return certPem;
    }

    /**
     * Build and sign an X.509 v2 CRL for the organization, listing every revoked cert.
     * Returns the DER-encoded CRL bytes, signed with the org CA private key.
     *
     * @param entries    one entry per revoked cert — (serial, revokedAt, reasonCode)
     * @param thisUpdate when this CRL was produced
     * @param nextUpdate when verifiers should fetch the next one
     * @param crlNumber  monotonically increasing per org (RFC 5280 §5.2.3)
     */
    public byte[] signCrl(Long orgId, Collection<CrlEntry> entries,
                          Instant thisUpdate, Instant nextUpdate, long crlNumber) {
        OrganizationCa orgCa = getOrCreateCa(orgId);
        PrivateKey caKey = keyEncryption.decrypt(orgCa.getCaKeyEncrypted(),
                orgCa.getCaKeyIv(), KEY_ALGORITHM);
        X509Certificate caCert = parseCertPem(orgCa.getCaCertPem());

        try {
            X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());
            X509v2CRLBuilder builder = new X509v2CRLBuilder(issuer, Date.from(thisUpdate));
            builder.setNextUpdate(Date.from(nextUpdate));

            for (CrlEntry entry : entries) {
                builder.addCRLEntry(entry.serialNumber(),
                        Date.from(entry.revokedAt()),
                        entry.reasonCode());
            }

            builder.addExtension(Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(crlNumber)));

            var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caKey);
            X509CRLHolder holder = builder.build(signer);
            return holder.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign CRL for org " + orgId, e);
        }
    }

    public SignedCertResult signCsr(Long orgId, String csrPem, int validityDays, List<String> sans) {
        OrganizationCa orgCa = getOrCreateCa(orgId);
        PrivateKey caKey = keyEncryption.decrypt(orgCa.getCaKeyEncrypted(), orgCa.getCaKeyIv(), KEY_ALGORITHM);
        X509Certificate caCert = parseCertPem(orgCa.getCaCertPem());
        PKCS10CertificationRequest csr = parseCsrPem(csrPem);

        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(validityDays));
        BigInteger serial = newSerial();

        X509Certificate signed = buildAndSign(caCert, caKey, csr, serial, now, expiry, sans,
                pkiProperties.crlUrlFor(orgId));
        return new SignedCertResult(toPem(signed), serial.toString(16), now, expiry);
    }

    private OrganizationCa getOrCreateCa(Long orgId) {
        return caRepository.findByOrganizationId(orgId).orElseGet(() -> createCa(orgId));
    }

    private OrganizationCa createCa(Long orgId) {
        KeyPair keyPair = generateKeyPair();
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(caProperties.caValidityDays()));
        X500Name subject = new X500Name("CN=EdgeGuardian CA Org-" + orgId + ",O=EdgeGuardian");

        X509Certificate caCert = selfSign(keyPair, subject, now, expiry);
        byte[] iv = keyEncryption.generateIv();
        byte[] encryptedKey = keyEncryption.encrypt(keyPair.getPrivate(), iv);
        String certPem = toPem(caCert);

        try {
            OrganizationCa saved = caRepository.save(OrganizationCa.builder()
                    .organizationId(orgId)
                    .caCertPem(certPem)
                    .caKeyEncrypted(encryptedKey)
                    .caKeyIv(iv)
                    .subject(subject.toString())
                    .notBefore(now)
                    .notAfter(expiry)
                    .build());
            log.info("Generated CA for organization {}", orgId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            log.debug("CA for org {} created by concurrent request, loading", orgId);
            return caRepository.findByOrganizationId(orgId)
                    .orElseThrow(() -> new IllegalStateException("CA missing after concurrent insert", e));
        }
    }

    private X509Certificate selfSign(KeyPair keyPair, X500Name subject, Instant notBefore, Instant notAfter) {
        try {
            SubjectPublicKeyInfo pubKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
            var builder = new X509v3CertificateBuilder(
                    subject, newSerial(), Date.from(notBefore), Date.from(notAfter), subject, pubKeyInfo);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

            var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.getPrivate());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to self-sign CA certificate", e);
        }
    }

    private X509Certificate buildAndSign(X509Certificate issuer, PrivateKey issuerKey,
                                         PKCS10CertificationRequest csr, BigInteger serial,
                                         Instant notBefore, Instant notAfter, List<String> sans,
                                         String crlUrl) {
        try {
            var builder = new X509v3CertificateBuilder(
                    new X500Name(issuer.getSubjectX500Principal().getName()),
                    serial, Date.from(notBefore), Date.from(notAfter),
                    csr.getSubject(), csr.getSubjectPublicKeyInfo());

            if (sans != null && !sans.isEmpty()) {
                GeneralName[] names = sans.stream()
                        .map(san -> san.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                                ? new GeneralName(GeneralName.iPAddress, san)
                                : new GeneralName(GeneralName.dNSName, san))
                        .toArray(GeneralName[]::new);
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
            }

            // CRL Distribution Point — tells TLS verifiers where to fetch revocation info.
            // Without this, EMQX's enable_crl_check has no URL to hit per-cert.
            GeneralName crlName = new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl);
            DistributionPointName dpName = new DistributionPointName(new GeneralNames(crlName));
            DistributionPoint dp = new DistributionPoint(dpName, null, null);
            builder.addExtension(Extension.cRLDistributionPoints, false,
                    new CRLDistPoint(new DistributionPoint[]{dp}));

            var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(issuerKey);
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign certificate", e);
        }
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(new ECGenParameterSpec(CURVE), SECURE_RANDOM);
            return keyGen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate ECDSA key pair", e);
        }
    }

    public record SignedCertResult(String certPem, String serialNumber,
                                   Instant notBefore, Instant notAfter) {
    }

    /** A single entry in the signed CRL. {@code reasonCode} uses RFC 5280 §5.3.1 values. */
    public record CrlEntry(BigInteger serialNumber, Instant revokedAt, int reasonCode) {
    }
}
