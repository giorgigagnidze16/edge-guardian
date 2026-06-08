package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.config.MqttProperties;
import com.edgeguardian.controller.model.DeviceManifestEntity;
import com.edgeguardian.controller.model.DeviceStatus;
import com.edgeguardian.controller.mqtt.payload.HeartbeatPayload;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Component;

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
    private final MqttProperties props;
    private final MqttSubscriptions subscriptions;
    private final ObjectMapper objectMapper;
    private final DeviceRegistry registry;

    @PostConstruct
    void register() {
        subscriptions.register("/device/+/heartbeat",
                MqttTopics.QOS_BEST_EFFORT, this::onHeartbeat);
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

            if (payload.autoUpdate() != null) {
                registry.recordAutoUpdate(payload.deviceId(), payload.autoUpdate());
            }

            Optional<DeviceManifestEntity> manifest = registry.getManifest(payload.deviceId());
            if (manifest.isPresent() && manifest.get().getVersion() > payload.manifestVersion()) {
                pushDesiredState(payload.deviceId(), manifest.get());
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

            String stateTopic = props.topicRoot() + "/device/" + deviceId + "/state/desired";
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
}
