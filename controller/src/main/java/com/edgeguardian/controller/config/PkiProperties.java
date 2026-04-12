package com.edgeguardian.controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edgeguardian.controller.pki")
public record PkiProperties(
        int crlValidityHours,
        String crlDistributionBaseUrl,
        String brokerCaPath
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

    public boolean hasBrokerCa() {
        return brokerCaPath != null && !brokerCaPath.isBlank();
    }
}
