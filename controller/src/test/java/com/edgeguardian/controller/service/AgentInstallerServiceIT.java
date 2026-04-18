package com.edgeguardian.controller.service;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.exception.NotFoundException;
import com.edgeguardian.controller.model.EnrollmentToken;
import com.edgeguardian.controller.repository.EnrollmentTokenRepository;
import com.edgeguardian.controller.service.AgentInstallerService.Os;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

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
        assertRejected(token.getId());
    }

    @Test
    void renderInstaller_expiredToken_throwsNotFound() {
        EnrollmentToken token = tokenRepository.save(freshToken()
                .expiresAt(Instant.now().minusSeconds(60)).build());
        assertRejected(token.getId());
    }

    @Test
    void renderInstaller_exhaustedMaxUses_throwsNotFound() {
        EnrollmentToken token = tokenRepository.save(freshToken().maxUses(1).useCount(1).build());
        assertRejected(token.getId());
    }

    @Test
    void renderInstaller_unknownTokenId_throwsNotFound() {
        assertRejected(99_999L);
    }

    @Test
    void renderInstaller_validToken_substitutesPlaceholders() throws Exception {
        EnrollmentToken token = tokenRepository.save(freshToken().build());
        String script = installers.renderInstaller(Os.WINDOWS, token.getId());

        assertThat(script)
                .doesNotContain("{{CONTROLLER_URL}}")
                .doesNotContain("{{ENROLLMENT_TOKEN}}")
                .contains(token.getToken())
                .contains("http://controller.test:30443")
                .contains("os=windows&arch=amd64");
    }

    private void assertRejected(Long tokenId) {
        assertThatThrownBy(() -> installers.renderInstaller(Os.WINDOWS, tokenId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Enrollment token not found");
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
