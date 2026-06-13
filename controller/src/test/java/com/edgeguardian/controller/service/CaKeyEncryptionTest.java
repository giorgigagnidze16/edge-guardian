package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.CaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaKeyEncryptionTest {

    private CaKeyEncryption build(String key, String... profiles) {
        var env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return new CaKeyEncryption(env, new CaProperties(key, 0, 0, 0));
    }

    @Test
    void blankKeyOutsideLocalProfileFailsFast() {
        CaKeyEncryption enc = build("", "prod");
        assertThrows(IllegalStateException.class, enc::init);
    }

    @Test
    void blankKeyInLocalProfileUsesDevFallback() {
        CaKeyEncryption enc = build("", "local");
        assertDoesNotThrow(enc::init);
    }

    @Test
    void validKeyInitialises() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        CaKeyEncryption enc = build(key, "prod");
        assertDoesNotThrow(enc::init);
    }
}
