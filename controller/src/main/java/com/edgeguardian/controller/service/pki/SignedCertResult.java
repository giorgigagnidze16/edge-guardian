package com.edgeguardian.controller.service.pki;

import java.time.Instant;

public record SignedCertResult(
        String certPem,
        String serialNumber,
        Instant notBefore,
        Instant notAfter
) {}
