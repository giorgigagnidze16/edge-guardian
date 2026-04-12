package com.edgeguardian.controller.service.pki;

import java.math.BigInteger;
import java.time.Instant;

/** One entry in a signed CRL. reasonCode uses RFC 5280 §5.3.1 values. */
public record CrlEntry(BigInteger serialNumber, Instant revokedAt, int reasonCode) {}
