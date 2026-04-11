package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.OTAService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handles device heartbeats over MQTT.
 * Subscribe: {topicRoot}/device/+/heartbeat
 * On heartbeat: updates device status, pushes manifest if outdated, sends pending commands.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatListener {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final DeviceRegistry registry;
    private final OTAService otaService;
    private final CommandPublisher commandPublisher;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, heartbeat subscription deferred");
            return;
        }

        String topic = topicRoot + "/device/+/heartbeat";
        try {
            var subscription = new MqttSubscription(topic, MqttTopics.QOS_BEST_EFFORT);
            IMqttMessageListener listener = this::onHeartbeat;
            mqttClient.subscribe(new MqttSubscription[]{subscription},
                    new IMqttMessageListener[]{listener});
            log.info("Subscribed to heartbeat topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to heartbeat topic: {}", e.getMessage());
        }
    }

    private void onHeartbeat(String topic, MqttMessage message) {
        try {
            var payload = objectMapper.readValue(message.getPayload(), HeartbeatPayload.class);

            if (payload.deviceId() == null || payload.deviceId().isBlank()) {
                log.warn("Heartbeat missing deviceId, topic={}", topic);
                return;
            }

            DeviceStatus status = payload.status() != null ? payload.status().toEntity() : null;
            var deviceOpt = registry.heartbeat(payload.deviceId(), status);
            if (deviceOpt.isEmpty()) {
                log.debug("Heartbeat from unknown device {}", payload.deviceId());
                return;
            }

            // Push manifest if device's version is outdated.
            Optional<DeviceManifestEntity> manifest = registry.getManifest(payload.deviceId());
            if (manifest.isPresent() && manifest.get().getVersion() > payload.manifestVersion()) {
                pushDesiredState(payload.deviceId(), manifest.get());
            }

            // Publish pending OTA commands.
            var pendingCommands = otaService.getPendingOtaCommands(payload.deviceId());
            for (var cmd : pendingCommands) {
                try {
                    @SuppressWarnings("unchecked")
                    var params = (Map<String, String>) cmd.get("params");
                    commandPublisher.publishCommand(payload.deviceId(),
                            (String) cmd.get("type"), params);
                } catch (MqttException e) {
                    log.error("Failed to publish pending command to device {}: {}",
                            payload.deviceId(), e.getMessage());
                }
            }

            log.debug("Heartbeat processed for device {}", payload.deviceId());

        } catch (Exception e) {
            log.error("Failed to process heartbeat from topic {}: {}", topic, e.getMessage());
        }
    }

    private void pushDesiredState(String deviceId, DeviceManifestEntity entity) {
        try {
            Map<String, Object> manifestMap = new LinkedHashMap<>();
            manifestMap.put("apiVersion", entity.getApiVersion());
            manifestMap.put("kind", entity.getKind());
            manifestMap.put("metadata", entity.getMetadata() != null ? entity.getMetadata() : Map.of());
            manifestMap.put("spec", entity.getSpec() != null ? entity.getSpec() : Map.of());
            manifestMap.put("version", entity.getVersion());

            String stateTopic = topicRoot + "/device/" + deviceId + "/state/desired";
            byte[] payload = objectMapper.writeValueAsBytes(manifestMap);
            var msg = new MqttMessage(payload);
            msg.setQos(MqttTopics.QOS_BEST_EFFORT);
            msg.setRetained(true);
            mqttClient.publish(stateTopic, msg);

            log.info("Pushed desired state to device {} (version {})", deviceId, entity.getVersion());
        } catch (Exception e) {
            log.error("Failed to push desired state to device {}: {}", deviceId, e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HeartbeatPayload(
            String deviceId,
            String agentVersion,
            DeviceStatusPayload status,
            long manifestVersion,
            Instant timestamp
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeviceStatusPayload(
            double cpuUsagePercent,
            long memoryUsedBytes,
            long memoryTotalBytes,
            long diskUsedBytes,
            long diskTotalBytes,
            double temperatureCelsius,
            long uptimeSeconds,
            String lastReconcile,
            String reconcileStatus
    ) {
        DeviceStatus toEntity() {
            var s = new DeviceStatus();
            s.setCpuUsagePercent(cpuUsagePercent);
            s.setMemoryUsedBytes(memoryUsedBytes);
            s.setMemoryTotalBytes(memoryTotalBytes);
            s.setDiskUsedBytes(diskUsedBytes);
            s.setDiskTotalBytes(diskTotalBytes);
            s.setTemperatureCelsius(temperatureCelsius);
            s.setUptimeSeconds(uptimeSeconds);
            if (lastReconcile != null && !lastReconcile.isEmpty()) {
                try { s.setLastReconcile(Instant.parse(lastReconcile)); } catch (Exception ignored) {}
            }
            s.setReconcileStatus(reconcileStatus != null ? reconcileStatus : "unknown");
            return s;
        }
    }
}
