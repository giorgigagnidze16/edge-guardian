package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.dto.AgentRegisterResponse;
import com.edgeguardian.controller.service.DeviceRegistry;
import com.edgeguardian.controller.service.EnrollmentService;
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

import java.util.Map;

/**
 * Handles device enrollment requests over MQTT.
 * Subscribe: {topicRoot}/device/+/enroll/request
 * Publish:   {topicRoot}/device/{deviceId}/enroll/response
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnrollmentListener {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final EnrollmentService enrollmentService;
    private final DeviceRegistry deviceRegistry;
    private final CommandPublisher publisher;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, enrollment subscription deferred");
            return;
        }

        String topic = topicRoot + "/device/+/enroll/request";
        try {
            var subscription = new MqttSubscription(topic, 1);
            IMqttMessageListener listener = this::onEnrollRequest;
            mqttClient.subscribe(new MqttSubscription[]{subscription},
                    new IMqttMessageListener[]{listener});
            log.info("Subscribed to enrollment topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to enrollment topic: {}", e.getMessage());
        }
    }

    private void onEnrollRequest(String topic, MqttMessage message) {
        try {
            var request = objectMapper.readValue(message.getPayload(), EnrollRequestPayload.class);

            if (request.deviceId() == null || request.deviceId().isBlank()) {
                publishResponse(request.deviceId(), false, "deviceId is required", null);
                return;
            }
            if (request.enrollmentToken() == null || request.enrollmentToken().isBlank()) {
                publishResponse(request.deviceId(), false, "enrollmentToken is required", null);
                return;
            }

            var result = enrollmentService.enrollDevice(
                    request.enrollmentToken(), request.deviceId(), request.hostname(),
                    request.architecture(), request.os(), request.agentVersion(), request.labels());

            Map<String, Object> manifestMap = deviceRegistry.getManifest(result.device().getDeviceId())
                    .map(entity -> {
                        Map<String, Object> map = new java.util.LinkedHashMap<>();
                        map.put("apiVersion", entity.getApiVersion());
                        map.put("kind", entity.getKind());
                        map.put("metadata", entity.getMetadata() != null ? entity.getMetadata() : Map.of());
                        map.put("spec", entity.getSpec() != null ? entity.getSpec() : Map.of());
                        map.put("version", entity.getVersion());
                        return map;
                    }).orElse(null);

            var response = new AgentRegisterResponse(true, "Device enrolled successfully",
                    manifestMap, result.deviceToken());

            publishResponse(request.deviceId(), response);
            log.info("Device {} enrolled via MQTT", request.deviceId());

        } catch (Exception e) {
            log.error("Failed to process enrollment request from topic {}: {}", topic, e.getMessage());
            String deviceId = extractDeviceId(topic);
            if (deviceId != null) {
                publishResponse(deviceId, false, e.getMessage(), null);
            }
        }
    }

    private void publishResponse(String deviceId, boolean accepted, String message, String token) {
        publishResponse(deviceId, new AgentRegisterResponse(accepted, message, null, token));
    }

    private void publishResponse(String deviceId, AgentRegisterResponse response) {
        if (deviceId == null) return;
        try {
            String responseTopic = topicRoot + "/device/" + deviceId + "/enroll/response";
            byte[] payload = objectMapper.writeValueAsBytes(response);
            var msg = new MqttMessage(payload);
            msg.setQos(1);
            msg.setRetained(false);
            mqttClient.publish(responseTopic, msg);
        } catch (Exception e) {
            log.error("Failed to publish enrollment response to device {}: {}", deviceId, e.getMessage());
        }
    }

    private String extractDeviceId(String topic) {
        String[] parts = topic.split("/");
        return parts.length >= 3 ? parts[2] : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnrollRequestPayload(
            String enrollmentToken,
            String deviceId,
            String hostname,
            String architecture,
            String os,
            String agentVersion,
            Map<String, String> labels
    ) {}
}
