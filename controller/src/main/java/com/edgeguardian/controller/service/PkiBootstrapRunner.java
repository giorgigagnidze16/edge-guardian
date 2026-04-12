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
                caService.getCaCertPem(org.getId());
                created++;
            }
        }
        log.info("PKI bootstrap: {} organization(s) checked, {} new CA(s) materialized",
                orgs.size(), created);
    }
}
