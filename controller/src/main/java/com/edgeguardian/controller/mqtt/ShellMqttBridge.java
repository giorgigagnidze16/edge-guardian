package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.config.MqttProperties;
import com.edgeguardian.controller.mqtt.payload.ShellEventPayload;
import com.edgeguardian.controller.service.ShellSessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges interactive shell sessions between the browser WebSocket and the
 * device over MQTT. Subscribes to per-session {@code out} and {@code event}
 * topics and publishes {@code open}/{@code in}/{@code ctl} to the device.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShellMqttBridge {

    private final MqttClient mqttClient;
    private final MqttProperties props;
    private final ObjectMapper objectMapper;
    private final MqttSubscriptions subscriptions;
    private final ShellSessionService sessionService;
    private final ShellWebSocketRegistry registry;

    @PostConstruct
    void register() {
        subscriptions.register("/device/+/shell/+/out", MqttTopics.QOS_RELIABLE, this::onOutput);
        subscriptions.register("/device/+/shell/event", MqttTopics.QOS_RELIABLE, this::onEvent);
    }

    private void onOutput(String topic, MqttMessage message) {
        String sessionId = sessionIdFromOutputTopic(topic);
        if (sessionId != null) {
            sessionService.deliverOutput(sessionId, message.getPayload());
        }
    }

    private void onEvent(String topic, MqttMessage message) {
        try {
            ShellEventPayload event = objectMapper.readValue(message.getPayload(), ShellEventPayload.class);
            if (event.sessionId() == null) {
                return;
            }
            if ("exited".equals(event.type()) || "error".equals(event.type())) {
                sessionService.close(event.sessionId(), "shell_" + event.type());
                registry.close(event.sessionId(), "shell " + event.type());
            }
        } catch (Exception e) {
            log.error("Failed to handle shell event from {}: {}", topic, e.getMessage());
        }
    }

    public void publishOpen(String deviceId, String sessionId, int rows, int cols) {
        publishJson(deviceId, "shell/open",
                Map.of("sessionId", sessionId, "rows", rows, "cols", cols));
    }

    public void publishInput(String deviceId, String sessionId, byte[] data) {
        publishRaw(deviceId, "shell/" + sessionId + "/in", data);
    }

    public void publishControl(String deviceId, String sessionId, byte[] controlJson) {
        publishRaw(deviceId, "shell/" + sessionId + "/ctl", controlJson);
    }

    public void publishClose(String deviceId, String sessionId) {
        publishJson(deviceId, "shell/" + sessionId + "/ctl", Map.of("type", "close"));
    }

    private void publishJson(String deviceId, String suffix, Object payload) {
        try {
            publishRaw(deviceId, suffix, objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize shell payload for {}/{}: {}", deviceId, suffix, e.getMessage());
        }
    }

    private void publishRaw(String deviceId, String suffix, byte[] payload) {
        String topic = props.topicRoot() + "/device/" + deviceId + "/" + suffix;
        try {
            MqttMessage message = new MqttMessage(payload);
            message.setQos(MqttTopics.QOS_RELIABLE);
            mqttClient.publish(topic, message);
        } catch (MqttException e) {
            log.error("Failed to publish shell message to {}: {}", topic, e.getMessage());
        }
    }

    /** Extracts {sessionId} from {root}/device/{id}/shell/{sessionId}/out. */
    private static String sessionIdFromOutputTopic(String topic) {
        String[] parts = topic.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : null;
    }
}
