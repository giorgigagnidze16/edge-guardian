package com.edgeguardian.controller.service;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.exception.NotFoundException;
import com.edgeguardian.controller.model.EnrollmentToken;
import com.edgeguardian.controller.repository.EnrollmentTokenRepository;
import com.edgeguardian.controller.service.installer.InstallerFormat;
import com.edgeguardian.controller.service.installer.Os;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({AgentInstallerService.class, AgentInstallerServiceIT.Mocks.class})
@TestPropertySource(properties = {
        "edgeguardian.controller.agent-installer.controller-url=http://controller.test:30443",
        "edgeguardian.controller.agent-installer.broker-url=tcp://broker.test:1883",
        "edgeguardian.controller.agent-installer.mtls-broker-url=ssl://broker.test:8883",
        "edgeguardian.controller.agent-installer.bootstrap-password=bootstrap-secret",
})
class AgentInstallerServiceIT extends AbstractIntegrationTest {

    private static final Long ORG = 1L;

    @Autowired private AgentInstallerService installers;
    @Autowired private EnrollmentTokenRepository tokenRepository;

    @BeforeEach
    void cleanSlate() {
        tokenRepository.deleteAll();
    }

    @Test
    void renderInstaller_revokedToken_throwsNotFound() {
        EnrollmentToken token = tokenRepository.save(freshToken().revoked(true).build());
        assertRejected(token.getToken());
    }

    @Test
    void renderInstaller_expiredToken_throwsNotFound() {
        EnrollmentToken token = tokenRepository.save(freshToken()
                .expiresAt(Instant.now().minusSeconds(60)).build());
        assertRejected(token.getToken());
    }

    @Test
    void renderInstaller_exhaustedMaxUses_throwsNotFound() {
        EnrollmentToken token = tokenRepository.save(freshToken().maxUses(1).useCount(1).build());
        assertRejected(token.getToken());
    }

    @Test
    void renderInstaller_unknownToken_throwsNotFound() {
        assertRejected("egt_does_not_exist");
    }

    @Test
    void renderInstaller_ps1_substitutesPlaceholders() throws Exception {
        EnrollmentToken token = tokenRepository.save(freshToken().build());
        String script = installers.renderInstaller(Os.WINDOWS, InstallerFormat.PS1, token.getToken(), "amd64");

        assertThat(script)
                .doesNotContain("{{CONTROLLER_URL}}")
                .doesNotContain("{{ENROLLMENT_TOKEN}}")
                .contains(token.getToken())
                .contains("http://controller.test:30443")
                .contains("os=windows&arch=amd64");
    }

    @Test
    void renderInstaller_cmd_embedsBase64Ps1Payload() throws Exception {
        EnrollmentToken token = tokenRepository.save(freshToken().build());
        String cmd = installers.renderInstaller(Os.WINDOWS, InstallerFormat.CMD, token.getToken(), "amd64");

        assertThat(cmd)
                .doesNotContain("{{PS1_BASE64_LINES}}")
                .contains("Start-Process -FilePath '%~f0' -Verb RunAs")
                .contains("net session")
                .contains("FromBase64String");

        String decoded = new String(Base64.getDecoder().decode(extractBase64Payload(cmd)),
                StandardCharsets.UTF_8);
        assertThat(decoded)
                .contains(token.getToken())
                .contains("http://controller.test:30443")
                .contains("$ServiceName = 'EdgeGuardianAgent'");
    }

    @Test
    void renderInstaller_formatIncompatibleWithOs_rejected() {
        assertThatThrownBy(() -> installers.renderInstaller(Os.LINUX, InstallerFormat.CMD, "x", "amd64"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void renderInstaller_linuxArm64_embedsArm64InBinaryUrl() throws Exception {
        EnrollmentToken token = tokenRepository.save(freshToken().build());
        String script = installers.renderInstaller(Os.LINUX, InstallerFormat.SHELL, token.getToken(), "arm64");

        assertThat(script)
                .contains("os=linux&arch=arm64")
                .doesNotContain("arch=amd64");
    }

    @Test
    void renderInstaller_darwinArm64_embedsLaunchdPlistAndArm64Binary() throws Exception {
        EnrollmentToken token = tokenRepository.save(freshToken().build());
        String script = installers.renderInstaller(
                Os.DARWIN, InstallerFormat.SHELL_DARWIN, token.getToken(), "arm64");

        assertThat(script)
                .doesNotContain("{{LAUNCHD_PLIST}}")
                .doesNotContain("{{BINARY_URL}}")
                .contains("os=darwin&arch=arm64")
                .contains("/Library/LaunchDaemons/com.edgeguardian.agent.plist")
                .contains("launchctl bootstrap system")
                .contains("<key>Label</key>")
                .contains("<string>com.edgeguardian.agent</string>");
    }

    private void assertRejected(String tokenSecret) {
        assertThatThrownBy(() -> installers.renderInstaller(Os.WINDOWS, InstallerFormat.PS1, tokenSecret, "amd64"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Enrollment token not found");
    }

    /** Join the base64 chunks emitted between the >"...payload.b64" ( and ) markers. */
    private static String extractBase64Payload(String cmd) {
        int start = cmd.indexOf("payload.b64\" (");
        int end = cmd.indexOf(")", start);
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(start);
        StringBuilder b64 = new StringBuilder();
        for (String line : cmd.substring(start, end).split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("echo ")) {
                b64.append(trimmed.substring(5));
            }
        }
        return b64.toString();
    }

    private EnrollmentToken.EnrollmentTokenBuilder freshToken() {
        long stamp = System.nanoTime();
        return EnrollmentToken.builder()
                .organizationId(ORG)
                .token("egt_test_" + stamp)
                .name("test-" + stamp);
    }

    @TestConfiguration
    static class Mocks {
        @Bean
        ArtifactStorageService artifactStorageService() {
            return Mockito.mock(ArtifactStorageService.class);
        }
    }
}
