package com.edgeguardian.controller.api;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.service.DeviceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@WebFluxTest(AgentApiController.class)
class AgentApiControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private DeviceRegistry registry;

    @Test
    void registerNewDevice() {
        Device device = new Device("rpi-001", "host", "arm64", "linux", "0.2.0");
        when(registry.register(eq("rpi-001"), eq("host"), eq("arm64"), eq("linux"), eq("0.2.0"), any()))
                .thenReturn(device);
        when(registry.getManifest("rpi-001")).thenReturn(Optional.empty());

        String body = """
                {
                    "deviceId": "rpi-001",
                    "hostname": "host",
                    "architecture": "arm64",
                    "os": "linux",
                    "agentVersion": "0.2.0"
                }
                """;

        webTestClient.post().uri("/api/v1/agent/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accepted").isEqualTo(true)
                .jsonPath("$.message").isEqualTo("Device registered successfully")
                .jsonPath("$.initialManifest").doesNotExist();
    }

    @Test
    void registerWithEmptyDeviceIdReturnsBadRequest() {
        String body = """
                {
                    "deviceId": "",
                    "hostname": "host"
                }
                """;

        webTestClient.post().uri("/api/v1/agent/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.accepted").isEqualTo(false);
    }

    @Test
    void registerReturnsManifestIfConfigured() {
        Device device = new Device("rpi-001", "host", "arm64", "linux", "0.2.0");
        when(registry.register(any(), any(), any(), any(), any(), any())).thenReturn(device);

        DeviceManifestEntity manifest = new DeviceManifestEntity("rpi-001",
                Map.of("name", "rpi-001"),
                Map.of("files", java.util.List.of()));
        when(registry.getManifest("rpi-001")).thenReturn(Optional.of(manifest));

        String body = """
                {
                    "deviceId": "rpi-001",
                    "hostname": "host",
                    "architecture": "arm64",
                    "os": "linux",
                    "agentVersion": "0.2.0"
                }
                """;

        webTestClient.post().uri("/api/v1/agent/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accepted").isEqualTo(true)
                .jsonPath("$.initialManifest").exists()
                .jsonPath("$.initialManifest.apiVersion").isEqualTo("edgeguardian/v1");
    }

    @Test
    void heartbeatUpdatesDeviceStatus() {
        Device device = new Device("rpi-001", "host", "arm64", "linux", "0.2.0");
        when(registry.heartbeat(eq("rpi-001"), any(DeviceStatus.class))).thenReturn(Optional.of(device));
        when(registry.getManifest("rpi-001")).thenReturn(Optional.empty());

        String body = """
                {
                    "deviceId": "rpi-001",
                    "agentVersion": "0.2.0",
                    "status": {
                        "cpuUsagePercent": 35.2,
                        "memoryUsedBytes": 256000000,
                        "reconcileStatus": "converged"
                    },
                    "timestamp": "2026-03-01T00:00:00Z"
                }
                """;

        webTestClient.post().uri("/api/v1/agent/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.manifestUpdated").isEqualTo(false);
    }

    @Test
    void heartbeatForUnknownDeviceReturnsNotFound() {
        when(registry.heartbeat(eq("unknown"), any())).thenReturn(Optional.empty());

        String body = """
                {
                    "deviceId": "unknown",
                    "agentVersion": "0.2.0",
                    "timestamp": "2026-03-01T00:00:00Z"
                }
                """;

        webTestClient.post().uri("/api/v1/agent/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getDesiredStateReturnsManifest() {
        DeviceManifestEntity manifest = new DeviceManifestEntity("rpi-001",
                Map.of("name", "rpi-001"),
                Map.of("files", java.util.List.of(Map.of("path", "/etc/test.conf", "content", "data"))));
        when(registry.getManifest("rpi-001")).thenReturn(Optional.of(manifest));

        webTestClient.get().uri("/api/v1/agent/desired-state/rpi-001")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.manifest").exists()
                .jsonPath("$.manifest.spec.files").isArray();
    }

    @Test
    void getDesiredStateReturnsEmptyWhenNoManifest() {
        when(registry.getManifest("rpi-001")).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/v1/agent/desired-state/rpi-001")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.manifest").doesNotExist()
                .jsonPath("$.version").isEqualTo(0);
    }

    @Test
    void reportStateAcknowledges() {
        when(registry.heartbeat(eq("rpi-001"), any())).thenReturn(Optional.of(
                new Device("rpi-001", "host", "arm64", "linux", "0.2.0")));

        String body = """
                {
                    "deviceId": "rpi-001",
                    "status": {
                        "cpuUsagePercent": 10.0
                    },
                    "timestamp": "2026-03-01T00:00:00Z"
                }
                """;

        webTestClient.post().uri("/api/v1/agent/report-state")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.acknowledged").isEqualTo(true);
    }
}
