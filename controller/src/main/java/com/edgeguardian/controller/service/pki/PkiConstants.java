package com.edgeguardian.controller.service.pki;

import java.math.BigInteger;
import java.security.SecureRandom;

public final class PkiConstants {

    public static final int SERIAL_BITS = 160;
    public static final String CURVE = "secp256r1";
    public static final String KEY_ALGORITHM = "EC";
    public static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PkiConstants() {}

    public static BigInteger newSerial() {
        return new BigInteger(SERIAL_BITS, SECURE_RANDOM);
    }
}
