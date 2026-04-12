package com.edgeguardian.controller.service;

import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.repository.OrganizationCaRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Ensures every registered organization has a materialized CA at controller startup.
 *
 * <p>Why this runs eagerly: downstream consumers (EMQX, mTLS-capable agents) need
 * the org CA bundle available the moment they first boot. If CA creation stays lazy
 * ("generate on first CSR"), the first agent to attempt enrollment also has to wait
 * for ECDSA key generation and multiple DB writes — which tends to exceed the 15 s
 * client timeout under load. Materializing on startup pushes that cost into a known
 * maintenance window.
 *
 * <p>In greenfield deployments where no {@code organizations} rows exist yet, this
 * runner is a no-op — the first organization created through the normal flow will
 * get its CA on the next restart (or lazily on its first CSR, whichever comes first).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PkiBootstrapRunner implements ApplicationRunner {

    private final OrganizationRepository organizationRepository;
    private final OrganizationCaRepository caRepository;
    private final CertificateAuthorityService caService;

    @Override
    public void run(ApplicationArguments args) {
        List<Organization> orgs = organizationRepository.findAll();
        if (orgs.isEmpty()) {
            log.info("PKI bootstrap: no organizations yet — nothing to materialize");
            return;
        }

        int created = 0;
        for (Organization org : orgs) {
            if (caRepository.findByOrganizationId(org.getId()).isEmpty()) {
                caService.getCaCertPem(org.getId()); // side-effect: creates + persists CA
                created++;
            }
        }
        log.info("PKI bootstrap: {} organization(s) checked, {} new CA(s) materialized",
                orgs.size(), created);
    }
}
