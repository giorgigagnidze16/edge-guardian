package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.PkiProperties;
import com.edgeguardian.controller.model.CertificateRevocationList;
import com.edgeguardian.controller.model.IssuedCertificate;
import com.edgeguardian.controller.model.RevokeReason;
import com.edgeguardian.controller.repository.CertificateRevocationListRepository;
import com.edgeguardian.controller.repository.IssuedCertificateRepository;
import com.edgeguardian.controller.service.pki.CrlEntry;
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

@Slf4j
@Service
@EnableConfigurationProperties(PkiProperties.class)
@RequiredArgsConstructor
public class CrlService {

    private final CertificateAuthorityService caService;
    private final IssuedCertificateRepository certRepository;
    private final CertificateRevocationListRepository crlRepository;
    private final PkiProperties pkiProperties;

    @Transactional
    public CertificateRevocationList rebuild(Long organizationId) {
        List<IssuedCertificate> revoked = certRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                .stream()
                .filter(IssuedCertificate::isRevoked)
                .toList();

        Instant now = Instant.now();
        Instant nextUpdate = now.plus(Duration.ofHours(pkiProperties.crlValidityHours()));

        List<CrlEntry> entries = revoked.stream()
                .map(c -> new CrlEntry(
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

    @Transactional
    public CertificateRevocationList getOrBuild(Long organizationId) {
        return crlRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> rebuild(organizationId));
    }

    private static BigInteger parseSerial(String hexSerial) {
        return new BigInteger(hexSerial, 16);
    }

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
