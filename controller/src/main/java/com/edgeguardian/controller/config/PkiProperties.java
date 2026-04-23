package com.edgeguardian.controller.config;

import com.edgeguardian.controller.api.ApiPaths;
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
            crlDistributionBaseUrl = ApiPaths.DEFAULT_CRL_DISTRIBUTION_BASE_URL;
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
