package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.PkiProperties;
import com.edgeguardian.controller.model.CertificateRevocationList;
import com.edgeguardian.controller.model.IssuedCertificate;
import com.edgeguardian.controller.model.RevokeReason;
import com.edgeguardian.controller.repository.CertificateRevocationListRepository;
import com.edgeguardian.controller.repository.IssuedCertificateRepository;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x509.CRLReason;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates and persists the per-organization X.509 v2 CRL.
 *
 * <p>Any time a cert is revoked, {@link #rebuild(Long)} is called to regenerate the CRL from
 * the current state of {@code certificates} and upsert a single row in {@code certificate_revocation_lists}.
 * The signed DER blob is then served publicly via {@code /api/v1/pki/crl/{orgId}.crl} and consumed
 * by TLS verifiers (EMQX, mTLS endpoints) on their configured refresh interval.
 *
 * <p>This is the single source of truth for "is this cert revoked" at the wire level — the DB
 * column {@code certificates.revoked} is the logical truth, but only the CRL is what TLS stacks see.
 */
@Slf4j
@Service
@EnableConfigurationProperties(PkiProperties.class)
@RequiredArgsConstructor
public class CrlService {

    private final CertificateAuthorityService caService;
    private final IssuedCertificateRepository certRepository;
    private final CertificateRevocationListRepository crlRepository;
    private final PkiProperties pkiProperties;

    /**
     * Regenerate the CRL for the given organization from scratch, based on current DB state.
     * Called after every revocation commit. Safe to call even if no certs are revoked yet —
     * we still publish an empty signed CRL so verifiers can fetch successfully.
     */
    @Transactional
    public CertificateRevocationList rebuild(Long organizationId) {
        List<IssuedCertificate> revoked = certRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                .stream()
                .filter(IssuedCertificate::isRevoked)
                .toList();

        Instant now = Instant.now();
        Instant nextUpdate = now.plus(Duration.ofHours(pkiProperties.crlValidityHours()));

        List<CertificateAuthorityService.CrlEntry> entries = revoked.stream()
                .map(c -> new CertificateAuthorityService.CrlEntry(
                        parseSerial(c.getSerialNumber()),
                        c.getRevokedAt() != null ? c.getRevokedAt() : now,
                        mapReason(c.getRevokeReason())))
                .toList();

        Optional<CertificateRevocationList> existing = crlRepository.findByOrganizationId(organizationId);
        long nextCrlNumber = existing.map(CertificateRevocationList::getCrlNumber).orElse(0L) + 1;

        byte[] der = caService.signCrl(organizationId, entries, now, nextUpdate, nextCrlNumber);

        CertificateRevocationList row = existing.orElseGet(() -> CertificateRevocationList.builder()
                .organizationId(organizationId)
                .build());
        row.setCrlDer(der);
        row.setThisUpdate(now);
        row.setNextUpdate(nextUpdate);
        row.setCrlNumber(nextCrlNumber);
        row.setRevokedCount(revoked.size());

        CertificateRevocationList saved = crlRepository.save(row);
        log.info("CRL rebuilt for org {} (#{}, {} revoked entries, valid until {})",
                organizationId, nextCrlNumber, revoked.size(), nextUpdate);
        return saved;
    }

    /**
     * Fetch the current CRL for an org. If none exists yet, build one on the fly so first-time
     * verifiers always see a valid signed document (with an empty revoked list).
     */
    @Transactional
    public CertificateRevocationList getOrBuild(Long organizationId) {
        return crlRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> rebuild(organizationId));
    }

    private static BigInteger parseSerial(String hexSerial) {
        // IssuedCertificate stores serial as lowercase hex (see CertificateAuthorityService#signCsr)
        return new BigInteger(hexSerial, 16);
    }

    /** Map our internal revoke reasons to RFC 5280 §5.3.1 CRL reason codes. */
    private static int mapReason(RevokeReason reason) {
        if (reason == null) {
            return CRLReason.unspecified;
        }
        return switch (reason) {
            case COMPROMISED -> CRLReason.keyCompromise;
            case RENEWED -> CRLReason.superseded;
            case DEVICE_DELETED -> CRLReason.cessationOfOperation;
            case ADMIN_REVOKED -> CRLReason.privilegeWithdrawn;
            case EXPIRED -> CRLReason.unspecified;
        };
    }
}
