package com.edgeguardian.controller.service;

import com.edgeguardian.controller.api.ApiPaths;
import com.edgeguardian.controller.config.AgentInstallerProperties;
import com.edgeguardian.controller.dto.AgentReleaseInfo;
import com.edgeguardian.controller.exception.NotFoundException;
import com.edgeguardian.controller.model.EnrollmentToken;
import com.edgeguardian.controller.repository.EnrollmentTokenRepository;
import com.edgeguardian.controller.service.installer.InstallerFormat;
import com.edgeguardian.controller.service.installer.Os;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentInstallerProperties.class)
public class AgentInstallerService {

    private static final int CMD_CHUNK_SIZE = 4000;
    private static final String BINARY_OBJECT_PREFIX = "public/agent/";
    private static final String RELEASE_MANIFEST = "release.json";
    private static final Pattern ARCH = Pattern.compile("^[a-z0-9]{1,16}$");

    private final AgentInstallerProperties props;
    private final ArtifactStorageService storage;
    private final EnrollmentTokenRepository tokenRepository;
    private final ObjectMapper objectMapper;

    @Value("${edgeguardian.controller.ota.public-key:}")
    private String otaPublicKey;

    @Value("${edgeguardian.controller.agent-installer.publish-token:}")
    private String publishToken;

    private static String stripTrailingNewline(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        if (s.endsWith("\r\n")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("\n") || s.endsWith("\r")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    public String renderInstaller(Os os, InstallerFormat format, String tokenSecret, String arch) throws IOException {
        format.validateFor(os);
        EnrollmentToken token = tokenRepository.findByToken(tokenSecret)
            .filter(EnrollmentToken::isValid)
            .orElseThrow(() -> new NotFoundException("Enrollment token not found"));

        Map<String, String> vars = payloadVars(os, token, arch);
        return switch (format) {
            case SHELL, SHELL_DARWIN, PS1 -> render(format.templatePath, vars);
            case CMD -> wrapAsSelfElevatingCmd(render(InstallerFormat.PS1.templatePath, vars));
        };
    }

    public InputStream openBinary(Os os, String arch) {
        validateArch(arch);
        String key = BINARY_OBJECT_PREFIX + os.slug + "/" + arch + "/" + os.binaryName;
        try {
            return storage.load(key);
        } catch (IOException e) {
            throw new NotFoundException("Agent binary not available: " + os.slug + "/" + arch);
        }
    }

    /**
     * Publishing the global agent binary is a platform-owner operation, gated by a
     * dedicated deploy token held only by the release pipeline - never by tenant
     * credentials. The Ed25519 signature check in {@link #storeBinary} remains the
     * authority on binary content; this token only authenticates the caller.
     */
    public void requirePublishToken(String provided) {
        if (publishToken == null || publishToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Binary publishing is disabled: no publish token configured");
        }
        byte[] expected = publishToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid publish token");
        }
    }

    public void storeBinary(Os os, String arch, byte[] data, String version, String signatureHex)
            throws IOException {
        validateArch(arch);
        if (version == null || version.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing version");
        }
        verifySignature(data, signatureHex);
        String sha256 = HexFormat.of().formatHex(sha256(data));
        String dir = BINARY_OBJECT_PREFIX + os.slug + "/" + arch + "/";
        storage.putRaw(dir + os.binaryName, new ByteArrayInputStream(data), data.length);

        AgentReleaseInfo release = new AgentReleaseInfo(version.trim(), sha256, signatureHex.trim());
        byte[] manifest = objectMapper.writeValueAsBytes(release);
        storage.putRaw(dir + RELEASE_MANIFEST, new ByteArrayInputStream(manifest), manifest.length);
        log.info("Agent binary published: {}/{} version={}", os.slug, arch, version.trim());
    }

    public AgentReleaseInfo latestRelease(Os os, String arch) {
        validateArch(arch);
        String key = BINARY_OBJECT_PREFIX + os.slug + "/" + arch + "/" + RELEASE_MANIFEST;
        try (InputStream in = storage.load(key)) {
            return objectMapper.readValue(in, AgentReleaseInfo.class);
        } catch (IOException e) {
            throw new NotFoundException("No agent release available: " + os.slug + "/" + arch);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void validateArch(String arch) {
        if (arch == null || !ARCH.matcher(arch).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid architecture");
        }
    }

    private void verifySignature(byte[] data, String signatureHex) {
        if (otaPublicKey == null || otaPublicKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Binary publishing is disabled: no OTA public key configured");
        }
        if (signatureHex == null || signatureHex.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing signature");
        }
        byte[] pub;
        byte[] sig;
        try {
            pub = HexFormat.of().parseHex(otaPublicKey.trim());
            sig = HexFormat.of().parseHex(signatureHex.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed signature or key");
        }
        if (pub.length != 32) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid OTA public key");
        }
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, new Ed25519PublicKeyParameters(pub, 0));
        signer.update(data, 0, data.length);
        if (!signer.verifySignature(sig)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature verification failed");
        }
    }

    private Map<String, String> payloadVars(Os os, EnrollmentToken token, String arch) throws IOException {
        String binaryUrl = UriComponentsBuilder.fromUriString(props.controllerUrl())
            .path(ApiPaths.AGENT_BINARY)
            .queryParam("os", os.slug)
            .queryParam("arch", arch)
            .build().toUriString();
        String systemdUnit  = os == Os.LINUX  ? loadResource("installers/edgeguardian-agent.service.tmpl") : "";
        String launchdPlist = os == Os.DARWIN ? loadResource("installers/com.edgeguardian.agent.plist.tmpl") : "";
        String logo = loadResource("installers/logo.txt");
        String logoBase64 = Base64.getEncoder().encodeToString(
            stripTrailingNewline(logo).getBytes(StandardCharsets.UTF_8));

        return Map.ofEntries(
            Map.entry("CONTROLLER_URL",     props.controllerUrl()),
            Map.entry("BROKER_URL",         props.brokerUrl()),
            Map.entry("MTLS_BROKER_URL",    props.mtlsBrokerUrl()),
            Map.entry("BOOTSTRAP_PASSWORD", props.bootstrapPassword()),
            Map.entry("ENROLLMENT_TOKEN",   token.getToken()),
            Map.entry("OTA_SIGN_KEY",       otaPublicKey == null ? "" : otaPublicKey.trim()),
            Map.entry("BINARY_URL",         binaryUrl),
            Map.entry("SYSTEMD_UNIT",       systemdUnit),
            Map.entry("LAUNCHD_PLIST",      launchdPlist),
            Map.entry("AGENT_VERSION",      props.agentVersion() == null ? "unknown" : props.agentVersion()),
            Map.entry("LOGO",               stripTrailingNewline(logo)),
            Map.entry("LOGO_BASE64",        logoBase64)
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
}
