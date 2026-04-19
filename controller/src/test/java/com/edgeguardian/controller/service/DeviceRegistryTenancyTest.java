package com.edgeguardian.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.model.Organization;
import com.edgeguardian.controller.repository.DeviceManifestRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.DeviceTelemetryRepository;
import com.edgeguardian.controller.repository.OrganizationRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Tenancy tests for the service-layer queries that DeviceController + TelemetryController
 * rely on to enforce org isolation. These verify that:
 *
 * <ul>
 *   <li>{@code findByIdForOrganization} returns empty when a device exists but belongs
 *       to a different org - callers should surface this as 404 and NOT as 403 so
 *       device existence isn't leaked via status codes.</li>
 *   <li>{@code findByOrganizationId} returns only the caller's org's devices.</li>
 *   <li>{@code countByOrganizationId} is per-org and doesn't leak global counts.</li>
 *   <li>{@code getLatestStatusForOrganization} is per-org.</li>
 * </ul>
 *
 * <p>A full MockMvc web-layer test would additionally verify {@code @PreAuthorize} and
 * HTTP status code mapping; the service-layer test here is the load-bearing correctness
 * check. If {@code findByIdForOrganization} leaks across orgs, every controller endpoint
 * that uses it becomes a tenancy bypass regardless of what the web layer does.
 */
@Import(DeviceRegistry.class)
class DeviceRegistryTenancyTest extends AbstractIntegrationTest {

    @Autowired private DeviceRegistry registry;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private DeviceManifestRepository manifestRepository;
    @Autowired private DeviceTelemetryRepository telemetryRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private Long orgA;
    private Long orgB;

    @BeforeEach
    void setUp() {
        orgA = organizationRepository.save(Organization.builder()
                .name("Org A").slug("tenancy-org-a").build()).getId();
        orgB = organizationRepository.save(Organization.builder()
                .name("Org B").slug("tenancy-org-b").build()).getId();
    }

    @AfterEach
    void tearDown() {
        telemetryRepository.deleteAll();
        manifestRepository.deleteAll();
        deviceRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void findByIdForOrganization_returnsEmptyForCrossTenantAccess() {
        registry.register(orgA, "rpi-only-in-a", "host-a", "arm64", "linux", "0.4.0");

        // Same-org lookup finds the device.
        Optional<Device> sameOrg = registry.findByIdForOrganization("rpi-only-in-a", orgA);
        assertThat(sameOrg).isPresent();

        // Cross-org lookup returns empty - the controller maps this to 404, preventing
        // org B admins from confirming whether "rpi-only-in-a" exists at all.
        Optional<Device> crossOrg = registry.findByIdForOrganization("rpi-only-in-a", orgB);
        assertThat(crossOrg).isEmpty();
    }

    @Test
    void findByOrganizationId_returnsOnlyOwnOrgDevices() {
        registry.register(orgA, "dev-a-1", "h", "arm64", "linux", "0.4.0");
        registry.register(orgA, "dev-a-2", "h", "arm64", "linux", "0.4.0");
        registry.register(orgB, "dev-b-1", "h", "arm64", "linux", "0.4.0");

        List<Device> aDevices = registry.findByOrganizationId(orgA);
        assertThat(aDevices).hasSize(2);
        assertThat(aDevices).extracting(Device::getDeviceId)
                .containsExactlyInAnyOrder("dev-a-1", "dev-a-2");

        List<Device> bDevices = registry.findByOrganizationId(orgB);
        assertThat(bDevices).hasSize(1);
        assertThat(bDevices).extracting(Device::getDeviceId)
                .containsExactly("dev-b-1");
    }

    @Test
    void countByOrganizationId_doesNotLeakGlobalCount() {
        registry.register(orgA, "a1", "h", "arm64", "linux", "0.4.0");
        registry.register(orgA, "a2", "h", "arm64", "linux", "0.4.0");
        registry.register(orgB, "b1", "h", "arm64", "linux", "0.4.0");
        registry.register(orgB, "b2", "h", "arm64", "linux", "0.4.0");
        registry.register(orgB, "b3", "h", "arm64", "linux", "0.4.0");

        assertThat(registry.countByOrganizationId(orgA)).isEqualTo(2);
        assertThat(registry.countByOrganizationId(orgB)).isEqualTo(3);
    }

    @Test
    void getLatestStatusForOrganization_scopesTelemetryByOrg() {
        registry.register(orgA, "a1", "h", "arm64", "linux", "0.4.0");
        registry.register(orgB, "b1", "h", "arm64", "linux", "0.4.0");

        registry.heartbeat("a1", DeviceStatus.builder()
                .cpuUsagePercent(11.0).memoryUsedBytes(1L).memoryTotalBytes(2L).build());
        registry.heartbeat("b1", DeviceStatus.builder()
                .cpuUsagePercent(22.0).memoryUsedBytes(1L).memoryTotalBytes(2L).build());

        Map<String, DeviceStatus> aStatuses = registry.getLatestStatusForOrganization(orgA);
        assertThat(aStatuses).containsOnlyKeys("a1");
        assertThat(aStatuses.get("a1").getCpuUsagePercent()).isEqualTo(11.0);

        Map<String, DeviceStatus> bStatuses = registry.getLatestStatusForOrganization(orgB);
        assertThat(bStatuses).containsOnlyKeys("b1");
        assertThat(bStatuses.get("b1").getCpuUsagePercent()).isEqualTo(22.0);
    }
}
