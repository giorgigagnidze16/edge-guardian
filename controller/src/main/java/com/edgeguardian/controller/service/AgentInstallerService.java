package com.edgeguardian.controller.service;

import com.edgeguardian.controller.config.AgentInstallerProperties;
import com.edgeguardian.controller.exception.NotFoundException;
import com.edgeguardian.controller.model.EnrollmentToken;
import com.edgeguardian.controller.repository.EnrollmentTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentInstallerProperties.class)
public class AgentInstallerService {

    private static final String BINARY_OBJECT_PREFIX = "public/agent/";

    private final EnrollmentTokenRepository tokenRepository;
    private final ArtifactStorageService storage;
    private final AgentInstallerProperties props;

    public String renderInstaller(Os os, Long tokenId) throws IOException {
        EnrollmentToken token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new NotFoundException("Enrollment token not found"));
        if (!token.isValid()) {
            throw new NotFoundException("Enrollment token not found");
        }

        String binaryUrl = props.controllerUrl() + "/api/v1/agent/binary?os=" + os.slug + "&arch=amd64";
        String systemdUnit = os == Os.LINUX ? loadResource("installers/edgeguardian-agent.service.tmpl") : "";

        Map<String, String> vars = Map.of(
                "CONTROLLER_URL", props.controllerUrl(),
                "BROKER_URL", props.brokerUrl(),
                "MTLS_BROKER_URL", props.mtlsBrokerUrl(),
                "BOOTSTRAP_PASSWORD", props.bootstrapPassword(),
                "ENROLLMENT_TOKEN", token.getToken(),
                "BINARY_URL", binaryUrl,
                "SYSTEMD_UNIT", systemdUnit
        );
        return replace(loadResource(os.templatePath), vars);
    }

    public InputStream openBinary(Os os, String arch) throws IOException {
        String key = BINARY_OBJECT_PREFIX + os.slug + "/" + arch + "/" + os.binaryName;
        try {
            return storage.load(key);
        } catch (IOException e) {
            throw new NotFoundException("Agent binary not available: " + os.slug + "/" + arch);
        }
    }

    public enum Os {
        LINUX("linux", "edgeguardian-agent", "installers/install.sh.tmpl"),
        WINDOWS("windows", "edgeguardian-agent.exe", "installers/install.ps1.tmpl");

        public final String slug;
        public final String binaryName;
        public final String templatePath;

        Os(String slug, String binaryName, String templatePath) {
            this.slug = slug;
            this.binaryName = binaryName;
            this.templatePath = templatePath;
        }

        public static Os of(String slug) {
            for (Os v : values()) {
                if (v.slug.equalsIgnoreCase(slug)) return v;
            }
            throw new IllegalArgumentException("Unsupported os: " + slug);
        }
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }

    private static String replace(String template, Map<String, String> vars) {
        String out = template;
        for (var e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }
}
