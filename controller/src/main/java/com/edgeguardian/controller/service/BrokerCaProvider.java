package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.PkiProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(PkiProperties.class)
public class BrokerCaProvider {

    private final PkiProperties pki;

    public BrokerCaProvider(PkiProperties pki) {
        this.pki = pki;
    }

    public String getPem() {
        if (!pki.hasBrokerCa()) {
            return "";
        }
        try {
            return Files.readString(Path.of(pki.brokerCaPath()));
        } catch (IOException e) {
            log.warn("Failed to read broker CA from {}: {}", pki.brokerCaPath(), e.getMessage());
            return "";
        }
    }
}
