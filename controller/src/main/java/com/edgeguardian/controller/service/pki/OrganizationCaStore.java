package com.edgeguardian.controller.service.pki;

import com.edgeguardian.controller.config.CaProperties;
import com.edgeguardian.controller.model.OrganizationCa;
import com.edgeguardian.controller.repository.OrganizationCaRepository;
import com.edgeguardian.controller.service.CaKeyEncryption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrganizationCaStore {

    private final CaProperties caProperties;
    private final CaKeyEncryption keyEncryption;
    private final OrganizationCaRepository caRepository;

    private final Map<Long, String> caCertCache = new ConcurrentHashMap<>();

    public OrganizationCa getOrCreate(Long orgId) {
        return caRepository.findByOrganizationId(orgId).orElseGet(() -> create(orgId));
    }

    public String getCaCertPem(Long orgId) {
        String cached = caCertCache.get(orgId);
        if (cached != null) return cached;
        String pem = getOrCreate(orgId).getCaCertPem();
        caCertCache.put(orgId, pem);
        return pem;
    }

    public LoadedCa loadForSigning(Long orgId) {
        OrganizationCa orgCa = getOrCreate(orgId);
        return new LoadedCa(
                PemCodec.parseCertificate(orgCa.getCaCertPem()),
                keyEncryption.decrypt(orgCa.getCaKeyEncrypted(), orgCa.getCaKeyIv(), PkiConstants.KEY_ALGORITHM));
    }

    private OrganizationCa create(Long orgId) {
        KeyPair keyPair = generateKeyPair();
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(caProperties.caValidityDays()));
        X500Name subject = new X500Name("CN=EdgeGuardian CA Org-" + orgId + ",O=EdgeGuardian");

        X509Certificate caCert = selfSign(keyPair, subject, now, expiry);
        byte[] iv = keyEncryption.generateIv();
        byte[] encryptedKey = keyEncryption.encrypt(keyPair.getPrivate(), iv);

        try {
            OrganizationCa saved = caRepository.save(OrganizationCa.builder()
                    .organizationId(orgId)
                    .caCertPem(PemCodec.write(caCert))
                    .caKeyEncrypted(encryptedKey)
                    .caKeyIv(iv)
                    .subject(subject.toString())
                    .notBefore(now)
                    .notAfter(expiry)
                    .build());
            log.info("Generated CA for organization {}", orgId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            return caRepository.findByOrganizationId(orgId)
                    .orElseThrow(() -> new IllegalStateException("CA missing after concurrent insert", e));
        }
    }

    private X509Certificate selfSign(KeyPair keyPair, X500Name subject, Instant notBefore, Instant notAfter) {
        try {
            SubjectPublicKeyInfo pubKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
            var builder = new X509v3CertificateBuilder(
                    subject, PkiConstants.newSerial(), Date.from(notBefore), Date.from(notAfter), subject, pubKeyInfo);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            var signer = new JcaContentSignerBuilder(PkiConstants.SIGNATURE_ALGORITHM).build(keyPair.getPrivate());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to self-sign CA certificate", e);
        }
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(PkiConstants.KEY_ALGORITHM);
            keyGen.initialize(new ECGenParameterSpec(PkiConstants.CURVE));
            return keyGen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate ECDSA key pair", e);
        }
    }
}
