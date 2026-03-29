package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.model.CommandExecution;
import com.edgeguardian.controller.repository.CommandExecutionRepository;
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

/**
 * Handles command execution results from devices over MQTT.
 * Subscribe: {topicRoot}/device/+/command/result
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandResultListener {

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;
    private final CommandExecutionRepository executionRepository;

    @Value("${edgeguardian.controller.mqtt.topic-root:edgeguardian}")
    private String topicRoot;

    @PostConstruct
    public void subscribe() {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT client not connected, command result subscription deferred");
            return;
        }

        String topic = topicRoot + "/device/+/command/result";
        try {
            var subscription = new MqttSubscription(topic, 1);
            IMqttMessageListener listener = this::onCommandResult;
            mqttClient.subscribe(new MqttSubscription[]{subscription},
                    new IMqttMessageListener[]{listener});
            log.info("Subscribed to command result topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to command result topic: {}", e.getMessage());
        }
    }

    private void onCommandResult(String topic, MqttMessage message) {
        try {
            var payload = objectMapper.readValue(message.getPayload(), CommandResultPayload.class);

            log.info("Command result: commandId={}, deviceId={}, phase={}, status={}, exitCode={}",
                    payload.commandId(), payload.deviceId(), payload.phase(),
                    payload.status(), payload.exitCode());

            executionRepository.save(CommandExecution.builder()
                    .commandId(payload.commandId())
                    .deviceId(payload.deviceId())
                    .phase(payload.phase())
                    .status(payload.status())
                    .exitCode(payload.exitCode())
                    .stdout(payload.stdout())
                    .stderr(payload.stderr())
                    .errorMessage(payload.errorMessage())
                    .durationMs(payload.durationMs())
                    .build());

        } catch (Exception e) {
            log.error("Failed to process command result from topic {}: {}", topic, e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommandResultPayload(
            String commandId,
            String deviceId,
            String phase,
            String status,
            int exitCode,
            String stdout,
            String stderr,
            String errorMessage,
            long durationMs,
            Instant timestamp
    ) {}
}
