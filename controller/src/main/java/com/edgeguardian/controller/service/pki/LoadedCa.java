package com.edgeguardian.controller.service.pki;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public record LoadedCa(X509Certificate cert, PrivateKey key) {
}
