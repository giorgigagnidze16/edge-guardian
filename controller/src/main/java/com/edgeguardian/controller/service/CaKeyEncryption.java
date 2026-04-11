package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.CaProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Encrypts and decrypts CA private keys using AES-256-GCM.
 * The master key is loaded from configuration or defaults to an insecure dev key.
 */
@Slf4j
@Component
@EnableConfigurationProperties(CaProperties.class)
public class CaKeyEncryption {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    static final int IV_BYTES = 12;

    private final CaProperties caProperties;
    private SecretKey masterKey;

    public CaKeyEncryption(CaProperties caProperties) {
        this.caProperties = caProperties;
    }

    @PostConstruct
    void init() {
        String configured = caProperties.encryptionKey();
        if (configured == null || configured.isBlank()) {
            log.warn("CA_ENCRYPTION_KEY not set — using insecure default. DO NOT use in production.");
            masterKey = new SecretKeySpec(
                    "edgeguardian-dev-key-not-4-prod!".getBytes(StandardCharsets.UTF_8), "AES");
        } else {
            byte[] decoded = Base64.getDecoder().decode(configured);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("CA_ENCRYPTION_KEY must be 32 bytes (base64-encoded)");
            }
            masterKey = new SecretKeySpec(decoded, "AES");
        }
    }

    public byte[] generateIv() {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    public byte[] encrypt(PrivateKey key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(key.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt private key", e);
        }
    }

    public PrivateKey decrypt(byte[] encrypted, byte[] iv, String algorithm) {
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pkcs8 = cipher.doFinal(encrypted);
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt private key", e);
        }
    }
}
