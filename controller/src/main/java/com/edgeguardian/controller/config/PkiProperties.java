package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PKI configuration for the CRL publication layer.
 *
 * @param crlValidityHours       how long each published CRL is valid; verifiers are expected
 *                               to refresh on or before {@code nextUpdate}.
 * @param crlDistributionBaseUrl base URL embedded as the CRL Distribution Point (CDP) extension
 *                               in every issued leaf cert. Verifiers (EMQX, mTLS clients) read
 *                               the CDP from the cert to know where to fetch the CRL.
 *                               Full URL format: {@code {base}/{orgId}.crl}.
 */
@ConfigurationProperties(prefix = "edgeguardian.controller.pki")
public record PkiProperties(
        int crlValidityHours,
        String crlDistributionBaseUrl
) {
    public PkiProperties {
        if (crlValidityHours <= 0) {
            crlValidityHours = 24;
        }
        if (crlDistributionBaseUrl == null || crlDistributionBaseUrl.isBlank()) {
            crlDistributionBaseUrl = "http://localhost:8443/api/v1/pki/crl";
        }
    }

    public String crlUrlFor(Long organizationId) {
        String base = crlDistributionBaseUrl.endsWith("/")
                ? crlDistributionBaseUrl.substring(0, crlDistributionBaseUrl.length() - 1)
                : crlDistributionBaseUrl;
        return base + "/" + organizationId + ".crl";
    }
}
