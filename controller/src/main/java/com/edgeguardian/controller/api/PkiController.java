package com.edgeguardian.controller.api;

import com.edgeguardian.controller.model.CertificateRevocationList;
import com.edgeguardian.controller.model.OrganizationCa;
import com.edgeguardian.controller.repository.OrganizationCaRepository;
import com.edgeguardian.controller.service.CrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Public PKI distribution endpoints.
 *
 * <p>These endpoints are intentionally unauthenticated — CRLs and CA certs are meant to be
 * publicly fetchable by any TLS verifier (EMQX broker, peers doing mTLS, offline tooling).
 * Publishing them here lets EMQX's {@code ssl.crl.url} point directly at the controller.
 */
@RestController
@RequestMapping("/api/v1/pki")
@RequiredArgsConstructor
public class PkiController {

    /** MIME type for DER-encoded CRLs per RFC 5280 §4.2.1.13 / RFC 2585 §3. */
    private static final MediaType APPLICATION_PKIX_CRL = new MediaType("application", "pkix-crl");
    /** MIME type for PEM-encoded cert bundles. */
    private static final MediaType APPLICATION_X_PEM_FILE = new MediaType("application", "x-pem-file");

    private final CrlService crlService;
    private final OrganizationCaRepository caRepository;

    @GetMapping("/crl/{orgId}.crl")
    public ResponseEntity<byte[]> getCrl(@PathVariable Long orgId) {
        CertificateRevocationList crl = crlService.getOrBuild(orgId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(APPLICATION_PKIX_CRL);
        headers.setContentDispositionFormData("attachment", "org-" + orgId + ".crl");
        headers.set("X-CRL-Number", String.valueOf(crl.getCrlNumber()));
        return new ResponseEntity<>(crl.getCrlDer(), headers, 200);
    }

    /**
     * Concatenated PEM bundle of every organization's CA certificate.
     *
     * <p>This is the trust store that EMQX's SSL listener consumes — {@code ssl.cacertfile}
     * must include every CA that could have issued a valid device cert. Without this bundle,
     * the broker has no way to validate client certs at TLS handshake.
     *
     * <p>Served unauthenticated: the bundle contains only public CA certificates, which are
     * meant to be distributed to verifiers. Private CA keys remain encrypted in the database.
     */
    @GetMapping("/ca-bundle")
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
}
