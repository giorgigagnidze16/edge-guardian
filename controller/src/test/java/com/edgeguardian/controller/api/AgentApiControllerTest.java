package com.edgeguardian.controller.api;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.repository.ApiKeyRepository;
import com.edgeguardian.controller.repository.UserRepository;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.EnrollmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentApiController.class)
class AgentApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceRegistry registry;

    @MockitoBean
    private EnrollmentService enrollmentService;

    @MockitoBean
    private ApiKeyRepository apiKeyRepository;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @WithMockUser
    void registerNewDevice() throws Exception {
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

        mockMvc.perform(post("/api/v1/agent/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.message").value("Device registered successfully"))
                .andExpect(jsonPath("$.initialManifest").doesNotExist());
    }

    @Test
    @WithMockUser
    void registerWithEmptyDeviceIdReturnsBadRequest() throws Exception {
        String body = """
                {
                    "deviceId": "",
                    "hostname": "host"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    @WithMockUser
    void registerReturnsManifestIfConfigured() throws Exception {
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

        mockMvc.perform(post("/api/v1/agent/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.initialManifest").exists())
                .andExpect(jsonPath("$.initialManifest.apiVersion").value("edgeguardian/v1"));
    }

    @Test
    @WithMockUser
    void heartbeatUpdatesDeviceStatus() throws Exception {
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

        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestUpdated").value(false));
    }

    @Test
    @WithMockUser
    void heartbeatForUnknownDeviceReturnsNotFound() throws Exception {
        when(registry.heartbeat(eq("unknown"), any())).thenReturn(Optional.empty());

        String body = """
                {
                    "deviceId": "unknown",
                    "agentVersion": "0.2.0",
                    "timestamp": "2026-03-01T00:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void getDesiredStateReturnsManifest() throws Exception {
        DeviceManifestEntity manifest = new DeviceManifestEntity("rpi-001",
                Map.of("name", "rpi-001"),
                Map.of("files", java.util.List.of(Map.of("path", "/etc/test.conf", "content", "data"))));
        when(registry.getManifest("rpi-001")).thenReturn(Optional.of(manifest));

        mockMvc.perform(get("/api/v1/agent/desired-state/rpi-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest").exists())
                .andExpect(jsonPath("$.manifest.spec.files").isArray());
    }

    @Test
    @WithMockUser
    void getDesiredStateReturnsEmptyWhenNoManifest() throws Exception {
        when(registry.getManifest("rpi-001")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/agent/desired-state/rpi-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest").doesNotExist())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @WithMockUser
    void reportStateAcknowledges() throws Exception {
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

        mockMvc.perform(post("/api/v1/agent/report-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true));
    }
}
