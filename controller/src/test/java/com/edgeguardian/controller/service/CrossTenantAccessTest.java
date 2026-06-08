package com.edgeguardian.controller.service;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.model.ApiKey;
import com.edgeguardian.controller.model.EnrollmentToken;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.repository.ApiKeyRepository;
import com.edgeguardian.controller.repository.EnrollmentTokenRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import com.edgeguardian.controller.service.pki.OrganizationCaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({
        EnrollmentService.class, ApiKeyService.class,
        DeviceRegistry.class, CertificateService.class, CertificateAuthorityService.class,
        OrganizationCaStore.class, CaKeyEncryption.class, AuditService.class, CrlService.class,
        CrossTenantAccessTest.Mocks.class,
})
class CrossTenantAccessTest extends AbstractIntegrationTest {

    @Autowired
    private EnrollmentService enrollmentService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private EnrollmentTokenRepository enrollmentTokenRepository;
    @Autowired
    private ApiKeyRepository apiKeyRepository;

    private Long orgA;
    private Long orgB;

    @BeforeEach
    void setUp() {
        orgA = organizationRepository.save(Organization.builder()
                .name("Org A").slug("tenant-x-orga").build()).getId();
        orgB = organizationRepository.save(Organization.builder()
                .name("Org B").slug("tenant-x-orgb").build()).getId();
    }

    @AfterEach
    void tearDown() {
        apiKeyRepository.deleteAll();
        enrollmentTokenRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void revokeEnrollmentToken_crossOrg_returns404() {
        EnrollmentToken orgBToken = enrollmentTokenRepository.save(EnrollmentToken.builder()
                .organizationId(orgB).token("egt_b_" + System.nanoTime()).name("b").build());

        assertNotFound(() -> enrollmentService.revokeToken(orgBToken.getId(), orgA));

        EnrollmentToken reloaded = enrollmentTokenRepository.findById(orgBToken.getId()).orElseThrow();
        assertThat(reloaded.isRevoked()).isFalse();
    }

    @Test
    void revokeApiKey_crossOrg_returns404() {
        ApiKey orgBKey = apiKeyRepository.save(ApiKey.builder()
                .organizationId(orgB).keyHash("hash-" + System.nanoTime())
                .keyPrefix("egk_").name("b-key").build());

        assertNotFound(() -> apiKeyService.revoke(orgBKey.getId(), orgA));

        ApiKey reloaded = apiKeyRepository.findById(orgBKey.getId()).orElseThrow();
        assertThat(reloaded.isRevoked()).isFalse();
    }

    private void assertNotFound(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @TestConfiguration
    static class Mocks {
        @Bean
        EmqxAdminClient emqxAdminClient() {
            return Mockito.mock(EmqxAdminClient.class);
        }

        @Bean
        com.edgeguardian.controller.mqtt.CommandPublisher commandPublisher() {
            return Mockito.mock(com.edgeguardian.controller.mqtt.CommandPublisher.class);
        }

        @Bean
        ArtifactStorageService artifactStorageService() {
            return Mockito.mock(ArtifactStorageService.class);
        }
    }
}
