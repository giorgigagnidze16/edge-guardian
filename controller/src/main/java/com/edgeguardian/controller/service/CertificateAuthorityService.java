package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.PkiProperties;
import com.edgeguardian.controller.service.pki.CrlEntry;
import com.edgeguardian.controller.service.pki.LoadedCa;
import com.edgeguardian.controller.service.pki.OrganizationCaStore;
import com.edgeguardian.controller.service.pki.PemCodec;
import com.edgeguardian.controller.service.pki.PkiConstants;
import com.edgeguardian.controller.service.pki.SignedCertResult;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CertificateAuthorityService {

    private final PkiProperties pkiProperties;
    private final OrganizationCaStore caStore;

    public String getCaCertPem(Long orgId) {
        return caStore.getCaCertPem(orgId);
    }

    public SignedCertResult signCsr(Long orgId, String csrPem, int validityDays, List<String> sans) {
        LoadedCa ca = caStore.loadForSigning(orgId);
        PKCS10CertificationRequest csr = PemCodec.parseCsr(csrPem);

        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(validityDays));
        BigInteger serial = PkiConstants.newSerial();

        X509Certificate signed = buildLeaf(ca, csr, serial, now, expiry, sans, pkiProperties.crlUrlFor(orgId));
        return new SignedCertResult(PemCodec.write(signed), serial.toString(16), now, expiry);
    }

    public byte[] signCrl(Long orgId, Collection<CrlEntry> entries,
                          Instant thisUpdate, Instant nextUpdate, long crlNumber) {
        LoadedCa ca = caStore.loadForSigning(orgId);
        try {
            X500Name issuer = new X500Name(ca.cert().getSubjectX500Principal().getName());
            X509v2CRLBuilder builder = new X509v2CRLBuilder(issuer, Date.from(thisUpdate));
            builder.setNextUpdate(Date.from(nextUpdate));
            for (CrlEntry e : entries) {
                builder.addCRLEntry(e.serialNumber(), Date.from(e.revokedAt()), e.reasonCode());
            }
            builder.addExtension(Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(crlNumber)));
            var signer = new JcaContentSignerBuilder(PkiConstants.SIGNATURE_ALGORITHM).build(ca.key());
            return builder.build(signer).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign CRL for org " + orgId, e);
        }
    }

    private X509Certificate buildLeaf(LoadedCa ca, PKCS10CertificationRequest csr, BigInteger serial,
                                      Instant notBefore, Instant notAfter, List<String> sans, String crlUrl) {
        try {
            var builder = new X509v3CertificateBuilder(
                    new X500Name(ca.cert().getSubjectX500Principal().getName()),
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

            GeneralName crlName = new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl);
            DistributionPoint dp = new DistributionPoint(
                    new DistributionPointName(new GeneralNames(crlName)), null, null);
            builder.addExtension(Extension.cRLDistributionPoints, false,
                    new CRLDistPoint(new DistributionPoint[]{dp}));

            var signer = new JcaContentSignerBuilder(PkiConstants.SIGNATURE_ALGORITHM).build(ca.key());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign certificate", e);
        }
    }
}
