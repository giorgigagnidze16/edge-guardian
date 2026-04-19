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
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentInstallerProperties.class)
public class AgentInstallerService {

    private static final int CMD_CHUNK_SIZE = 4000;
    private static final String BINARY_OBJECT_PREFIX = "public/agent/";

    private final AgentInstallerProperties props;
    private final ArtifactStorageService storage;
    private final EnrollmentTokenRepository tokenRepository;

    public String renderInstaller(Os os, InstallerFormat format, String tokenSecret) throws IOException {
        format.validateFor(os);
        EnrollmentToken token = tokenRepository.findByToken(tokenSecret)
                .filter(EnrollmentToken::isValid)
                .orElseThrow(() -> new NotFoundException("Enrollment token not found"));

        Map<String, String> vars = payloadVars(os, token);
        return switch (format) {
            case SHELL, PS1 -> render(format.templatePath, vars);
            case CMD -> wrapAsSelfElevatingCmd(render(InstallerFormat.PS1.templatePath, vars));
        };
    }

    public InputStream openBinary(Os os, String arch) {
        String key = BINARY_OBJECT_PREFIX + os.slug + "/" + arch + "/" + os.binaryName;
        try {
            return storage.load(key);
        } catch (IOException e) {
            throw new NotFoundException("Agent binary not available: " + os.slug + "/" + arch);
        }
    }

    private Map<String, String> payloadVars(Os os, EnrollmentToken token) throws IOException {
        String binaryUrl = UriComponentsBuilder.fromUriString(props.controllerUrl())
                .path("/api/v1/agent/binary")
                .queryParam("os", os.slug)
                .queryParam("arch", "amd64")
                .build().toUriString();
        String systemdUnit = os == Os.LINUX ? loadResource("installers/edgeguardian-agent.service.tmpl") : "";

        return Map.of(
                "CONTROLLER_URL", props.controllerUrl(),
                "BROKER_URL", props.brokerUrl(),
                "MTLS_BROKER_URL", props.mtlsBrokerUrl(),
                "BOOTSTRAP_PASSWORD", props.bootstrapPassword(),
                "ENROLLMENT_TOKEN", token.getToken(),
                "BINARY_URL", binaryUrl,
                "SYSTEMD_UNIT", systemdUnit
        );
    }

    /**
     * Wrap a PowerShell installer as a self-contained, self-elevating .cmd.
     */
    private String wrapAsSelfElevatingCmd(String ps1) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(ps1.getBytes(StandardCharsets.UTF_8));
        StringBuilder lines = new StringBuilder(b64.length() + 64);
        for (int i = 0; i < b64.length(); i += CMD_CHUNK_SIZE) {
            int end = Math.min(i + CMD_CHUNK_SIZE, b64.length());
            lines.append("echo ").append(b64, i, end).append("\r\n");
        }
        return render(InstallerFormat.CMD.templatePath, Map.of("PS1_BASE64_LINES", lines.toString()));
    }

    private String render(String classpathTemplate, Map<String, String> vars) throws IOException {
        String out = loadResource(classpathTemplate);
        for (var e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    private String loadResource(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }

    public enum Os {
        LINUX("linux", "edgeguardian-agent"),
        WINDOWS("windows", "edgeguardian-agent.exe");

        public final String slug;
        public final String binaryName;

        Os(String slug, String binaryName) {
            this.slug = slug;
            this.binaryName = binaryName;
        }

        public static Os of(String slug) {
            for (Os v : values()) {
                if (v.slug.equalsIgnoreCase(slug)) return v;
            }
            throw new IllegalArgumentException("Unsupported os: " + slug);
        }
    }

    public enum InstallerFormat {
        SHELL("install.sh", "installers/install.sh.tmpl", Os.LINUX),
        PS1("install.ps1", "installers/install.ps1.tmpl", Os.WINDOWS),
        CMD("EdgeGuardianInstall.cmd", "installers/install.cmd.tmpl", Os.WINDOWS);

        public final String filename;
        public final String templatePath;
        public final Os os;

        InstallerFormat(String filename, String templatePath, Os os) {
            this.filename = filename;
            this.templatePath = templatePath;
            this.os = os;
        }

        public static InstallerFormat defaultFor(Os os) {
            return switch (os) {
                case LINUX -> SHELL;
                case WINDOWS -> CMD;
            };
        }

        public static InstallerFormat resolve(Os os, String name) {
            if (name == null || name.isBlank()) return defaultFor(os);
            InstallerFormat fmt = Arrays.stream(values())
                    .filter(f -> f.name().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown installer format: " + name));
            fmt.validateFor(os);
            return fmt;
        }

        void validateFor(Os os) {
            if (this.os != os) {
                throw new IllegalArgumentException(
                        "Installer format " + this + " is not supported for os " + os.slug);
            }
        }
    }
}
