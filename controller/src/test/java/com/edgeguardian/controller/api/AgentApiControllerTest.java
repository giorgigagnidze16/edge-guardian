package com.edgeguardian.controller.api;

import com.edgeguardian.controller.model.Device;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.model.DeviceToken;
import com.edgeguardian.controller.repository.ApiKeyRepository;
import com.edgeguardian.controller.repository.DeviceRepository;
import com.edgeguardian.controller.repository.DeviceTokenRepository;
import com.edgeguardian.controller.repository.UserRepository;
import com.edgeguardian.controller.security.ApiKeyAuthenticationFilter;
import com.edgeguardian.controller.service.ArtifactStorageService;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.EnrollmentService;
import com.edgeguardian.controller.service.OTAService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

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

    @MockitoBean
    private DeviceTokenRepository deviceTokenRepository;

    @MockitoBean
    private DeviceRepository deviceRepository;

    @MockitoBean
    private OTAService otaService;

    @MockitoBean
    private ArtifactStorageService artifactStorageService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        // Device tokens are not JWTs — make the decoder throw so BearerTokenAuthenticationFilter
        // falls through gracefully instead of NPE-ing on null.
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException("Not a JWT"));
    }

    // --- Enrollment tests ---

    @Test
    @WithMockUser
    void enrollDevice_withValidToken_returnsAcceptedAndDeviceToken() throws Exception {
        Device device = new Device("rpi-001", "host", "arm64", "linux", "0.3.0");
        device.setOrganizationId(1L);
        var result = new EnrollmentService.EnrollmentResult(device, "edt_test-device-token");
        when(enrollmentService.enrollDevice(eq("egt_valid-token"), eq("rpi-001"), eq("host"),
                eq("arm64"), eq("linux"), eq("0.3.0"), any()))
                .thenReturn(result);
        when(registry.getManifest("rpi-001")).thenReturn(Optional.empty());

        String body = """
                {
                    "enrollmentToken": "egt_valid-token",
                    "deviceId": "rpi-001",
                    "hostname": "host",
                    "architecture": "arm64",
                    "os": "linux",
                    "agentVersion": "0.3.0"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.message").value("Device enrolled successfully"))
                .andExpect(jsonPath("$.deviceToken").value("edt_test-device-token"))
                .andExpect(jsonPath("$.initialManifest").doesNotExist());
    }

    @Test
    @WithMockUser
    void enrollDevice_withInvalidToken_returns401() throws Exception {
        when(enrollmentService.enrollDevice(eq("egt_bad-token"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid enrollment token"));

        String body = """
                {
                    "enrollmentToken": "egt_bad-token",
                    "deviceId": "rpi-001",
                    "hostname": "host",
                    "architecture": "arm64",
                    "os": "linux",
                    "agentVersion": "0.3.0"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void enrollDevice_withExpiredToken_returns401() throws Exception {
        when(enrollmentService.enrollDevice(eq("egt_expired"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Enrollment token expired or revoked"));

        String body = """
                {
                    "enrollmentToken": "egt_expired",
                    "deviceId": "rpi-001",
                    "hostname": "host",
                    "architecture": "arm64",
                    "os": "linux",
                    "agentVersion": "0.3.0"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void enrollDevice_withEmptyDeviceId_returnsBadRequest() throws Exception {
        String body = """
                {
                    "enrollmentToken": "egt_valid",
                    "deviceId": "",
                    "hostname": "host"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    @WithMockUser
    void enrollDevice_withMissingToken_returnsBadRequest() throws Exception {
        String body = """
                {
                    "deviceId": "rpi-001",
                    "hostname": "host"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    @WithMockUser
    void enrollDevice_withManifest_returnsManifest() throws Exception {
        Device device = new Device("rpi-001", "host", "arm64", "linux", "0.3.0");
        device.setOrganizationId(1L);
        var result = new EnrollmentService.EnrollmentResult(device, "edt_token");
        when(enrollmentService.enrollDevice(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);

        DeviceManifestEntity manifest = new DeviceManifestEntity("rpi-001",
                Map.of("name", "rpi-001"),
                Map.of("files", java.util.List.of()));
        when(registry.getManifest("rpi-001")).thenReturn(Optional.of(manifest));

        String body = """
                {
                    "enrollmentToken": "egt_valid",
                    "deviceId": "rpi-001",
                    "hostname": "host",
                    "architecture": "arm64",
                    "os": "linux",
                    "agentVersion": "0.3.0"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.deviceToken").value("edt_token"))
                .andExpect(jsonPath("$.initialManifest").exists())
                .andExpect(jsonPath("$.initialManifest.apiVersion").value("edgeguardian/v1"));
    }

    // --- Device token auth tests ---

    @Test
    @WithMockUser(roles = "DEVICE")
    void heartbeat_withDeviceToken_succeeds() throws Exception {
        // Device token filter authentication is tested via the 401 rejection tests below.
        // Here we verify the controller logic works for an authenticated device request.
        Device device = new Device("rpi-001", "host", "arm64", "linux", "0.3.0");
        device.setOrganizationId(1L);
        when(registry.heartbeat(eq("rpi-001"), any(DeviceStatus.class))).thenReturn(Optional.of(device));
        when(registry.getManifest("rpi-001")).thenReturn(Optional.empty());

        String body = """
                {
                    "deviceId": "rpi-001",
                    "agentVersion": "0.3.0",
                    "status": {
                        "cpuUsagePercent": 35.2,
                        "reconcileStatus": "converged"
                    },
                    "timestamp": "2026-03-01T00:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Device-Token", "edt_valid-device-token")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestUpdated").value(false));
    }

    @Test
    void heartbeat_withoutDeviceToken_returns401() throws Exception {
        String body = """
                {
                    "deviceId": "rpi-001",
                    "agentVersion": "0.3.0",
                    "timestamp": "2026-03-01T00:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeat_withRevokedToken_returns401() throws Exception {
        String rawToken = "edt_revoked-token";
        String tokenHash = ApiKeyAuthenticationFilter.sha256(rawToken);
        DeviceToken deviceToken = DeviceToken.builder()
                .deviceId("rpi-001")
                .tokenHash(tokenHash)
                .tokenPrefix("edt_revoked-")
                .revoked(true)
                .build();
        when(deviceTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(deviceToken));

        String body = """
                {
                    "deviceId": "rpi-001",
                    "agentVersion": "0.3.0",
                    "timestamp": "2026-03-01T00:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Device-Token", rawToken)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // --- Existing endpoint tests (with auth) ---

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
