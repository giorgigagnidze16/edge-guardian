package com.edgeguardian.controller.service.pki;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

public final class PemCodec {

    private PemCodec() {}

    public static X509Certificate parseCertificate(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            return new JcaX509CertificateConverter().getCertificate((X509CertificateHolder) parser.readObject());
        } catch (IOException | CertificateException e) {
            throw new IllegalArgumentException("Invalid certificate PEM", e);
        }
    }

    public static PKCS10CertificationRequest parseCsr(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            return (PKCS10CertificationRequest) parser.readObject();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid CSR PEM", e);
        }
    }

    public static String write(Object obj) {
        try {
            StringWriter sw = new StringWriter();
            try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
                writer.writeObject(obj);
            }
            return sw.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode PEM", e);
        }
    }
}
