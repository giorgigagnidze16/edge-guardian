package com.edgeguardian.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.edgeguardian.controller.AbstractIntegrationTest;
import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.model.DeviceTelemetry;
import com.edgeguardian.controller.repository.DeviceTelemetryRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DeviceRegistry.class)
class DeviceRegistryTest extends AbstractIntegrationTest {

    @Autowired
    private DeviceRegistry registry;

    @Autowired
    private DeviceTelemetryRepository telemetryRepository;

    @Test
    void registerNewDevice() {
        Device device = registry.register("rpi-001", "raspberrypi", "arm64", "linux", "0.2.0");

        assertThat(device.getDeviceId()).isEqualTo("rpi-001");
        assertThat(device.getHostname()).isEqualTo("raspberrypi");
        assertThat(device.getArchitecture()).isEqualTo("arm64");
        assertThat(device.getOs()).isEqualTo("linux");
        assertThat(device.getState()).isEqualTo(Device.DeviceState.ONLINE);
        assertThat(registry.count()).isEqualTo(1);
    }

    @Test
    void registerExistingDeviceUpdatesFields() {
        registry.register("rpi-001", "old-host", "arm64", "linux", "0.1.0");
        Device updated = registry.register("rpi-001", "new-host", "arm64", "linux", "0.2.0");

        assertThat(updated.getHostname()).isEqualTo("new-host");
        assertThat(updated.getAgentVersion()).isEqualTo("0.2.0");
        assertThat(registry.count()).isEqualTo(1);
    }

    @Test
    void registerWithLabels() {
        Map<String, String> labels = Map.of("env", "production", "site", "factory-1");
        Device device = registry.register("rpi-001", "host", "arm64", "linux", "0.2.0", labels);

        assertThat(device.getLabels()).containsEntry("env", "production");
        assertThat(device.getLabels()).containsEntry("site", "factory-1");
    }

    @Test
    void heartbeatUpdatesTimestampAndInsertsTelemetry() {
        registry.register("rpi-001", "host", "arm64", "linux", "0.2.0");

        DeviceStatus status = new DeviceStatus();
        status.setCpuUsagePercent(42.5);
        status.setMemoryUsedBytes(512_000_000L);
        status.setReconcileStatus("converged");

        Optional<Device> result = registry.heartbeat("rpi-001", status);

        assertThat(result).isPresent();
        assertThat(result.get().getLastHeartbeat()).isNotNull();
        assertThat(result.get().getState()).isEqualTo(Device.DeviceState.ONLINE);

        // Verify telemetry was inserted
        Optional<DeviceTelemetry> telemetry = telemetryRepository.findLatestByDeviceId("rpi-001");
        assertThat(telemetry).isPresent();
        assertThat(telemetry.get().getCpuUsagePercent()).isEqualTo(42.5);
        assertThat(telemetry.get().getMemoryUsedBytes()).isEqualTo(512_000_000L);
        assertThat(telemetry.get().getReconcileStatus()).isEqualTo("converged");
    }

    @Test
    void heartbeatForUnknownDeviceReturnsEmpty() {
        Optional<Device> result = registry.heartbeat("nonexistent", null);
        assertThat(result).isEmpty();
    }

    @Test
    void getLatestStatusReturnsTelemetry() {
        registry.register("rpi-001", "host", "arm64", "linux", "0.2.0");

        DeviceStatus status = DeviceStatus.builder()
            .cpuUsagePercent(55.0)
            .memoryUsedBytes(1_000_000L)
            .memoryTotalBytes(2_000_000L)
            .build();
        registry.heartbeat("rpi-001", status);

        Optional<DeviceStatus> latest = registry.getLatestStatus("rpi-001");
        assertThat(latest).isPresent();
        assertThat(latest.get().getCpuUsagePercent()).isEqualTo(55.0);
        assertThat(latest.get().getMemoryUsedBytes()).isEqualTo(1_000_000L);
    }

    @Test
    void findByIdReturnsDevice() {
        registry.register("rpi-001", "host", "arm64", "linux", "0.2.0");

        assertThat(registry.findById("rpi-001")).isPresent();
        assertThat(registry.findById("nonexistent")).isEmpty();
    }

    @Test
    void removeDeviceDeletesDeviceAndManifest() {
        registry.register("rpi-001", "host", "arm64", "linux", "0.2.0");
        registry.saveManifest("rpi-001", Map.of("name", "rpi-001"), Map.of());

        assertThat(registry.remove("rpi-001")).isTrue();
        assertThat(registry.findById("rpi-001")).isEmpty();
        assertThat(registry.getManifest("rpi-001")).isEmpty();
        assertThat(registry.count()).isEqualTo(0);
    }

    @Test
    void removeNonexistentDeviceReturnsFalse() {
        assertThat(registry.remove("nonexistent")).isFalse();
    }

    @Test
    void saveAndGetManifest() {
        registry.register("rpi-001", "host", "arm64", "linux", "0.2.0");

        Map<String, Object> metadata = Map.of("name", "rpi-001");
        Map<String, Object> spec = Map.of("files", java.util.List.of(
            Map.of("path", "/etc/test.conf", "content", "hello")
        ));

        DeviceManifestEntity entity = registry.saveManifest("rpi-001", metadata, spec);
        assertThat(entity.getVersion()).isEqualTo(1);

        Optional<DeviceManifestEntity> loaded = registry.getManifest("rpi-001");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSpec()).containsKey("files");
    }

    @Test
    void saveManifestIncrementsVersion() {
        registry.register("rpi-001", "host", "arm64", "linux", "0.2.0");

        registry.saveManifest("rpi-001", Map.of(), Map.of("v", 1));
        DeviceManifestEntity updated = registry.saveManifest("rpi-001", Map.of(), Map.of("v", 2));

        assertThat(updated.getVersion()).isEqualTo(2);
    }

    @Test
    void findAllReturnsAllDevices() {
        registry.register("rpi-001", "host1", "arm64", "linux", "0.2.0");
        registry.register("rpi-002", "host2", "arm", "linux", "0.2.0");
        registry.register("rpi-003", "host3", "amd64", "linux", "0.2.0");

        assertThat(registry.findAll()).hasSize(3);
        assertThat(registry.count()).isEqualTo(3);
    }
}
