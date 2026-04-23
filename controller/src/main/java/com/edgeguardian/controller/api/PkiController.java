package com.edgeguardian.controller.api;

import com.edgeguardian.controller.model.CertificateRevocationList;
import com.edgeguardian.controller.model.OrganizationCa;
import com.edgeguardian.controller.repository.OrganizationCaRepository;
import com.edgeguardian.controller.service.BrokerCaProvider;
import com.edgeguardian.controller.service.CrlService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.PKI_BASE)
@RequiredArgsConstructor
public class PkiController {

    private static final MediaType APPLICATION_PKIX_CRL =
        new MediaType("application", "pkix-crl");

    private static final MediaType APPLICATION_X_PEM_FILE =
        new MediaType("application", "x-pem-file");

    private final CrlService crlService;
    private final OrganizationCaRepository caRepository;
    private final BrokerCaProvider brokerCaProvider;

    @GetMapping(ApiPaths.PKI_CRL_FILE_PATH)
    public ResponseEntity<byte[]> getCrl(@PathVariable Long orgId) {
        CertificateRevocationList crl = crlService.getOrBuild(orgId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(APPLICATION_PKIX_CRL);
        headers.setContentDispositionFormData("attachment", "org-" + orgId + ".crl");
        headers.set("X-CRL-Number", String.valueOf(crl.getCrlNumber()));
        return new ResponseEntity<>(crl.getCrlDer(), headers, 200);
    }

    @GetMapping(ApiPaths.PKI_CA_BUNDLE_PATH)
    public ResponseEntity<byte[]> getCaBundle() {
        List<OrganizationCa> all = caRepository.findAll();
        String concatenated = all.stream()
            .map(OrganizationCa::getCaCertPem)
            .map(pem -> pem.endsWith("\n") ? pem : pem + "\n")
            .collect(Collectors.joining());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(APPLICATION_X_PEM_FILE);
        headers.setContentDispositionFormData("attachment", "edgeguardian-ca-bundle.pem");
        headers.set("X-CA-Count", String.valueOf(all.size()));
        return new ResponseEntity<>(concatenated.getBytes(StandardCharsets.UTF_8), headers, 200);
    }

    @GetMapping(value = ApiPaths.PKI_BROKER_CA_PATH, produces = "application/x-pem-file")
    public ResponseEntity<byte[]> getBrokerCa() {
        String pem = brokerCaProvider.getPem();
        if (pem.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(APPLICATION_X_PEM_FILE);
        headers.setContentDispositionFormData("attachment", "broker-ca.pem");
        return new ResponseEntity<>(pem.getBytes(StandardCharsets.UTF_8), headers, 200);
    }
}
