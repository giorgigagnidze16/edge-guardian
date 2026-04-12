package com.edgeguardian.controller.service.result;

import com.edgeguardian.controller.model.CertificateRequest;
import com.edgeguardian.controller.model.IssuedCertificate;

public record CertRequestResult(CertificateRequest request, IssuedCertificate certificate,
                                boolean blocked) {}
