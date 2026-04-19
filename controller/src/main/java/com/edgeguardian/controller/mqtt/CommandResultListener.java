package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.model.CommandExecution;
import com.edgeguardian.controller.mqtt.payload.CommandResultPayload;
import com.edgeguardian.controller.repository.CommandExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Component;

/**
 * Handles command execution results from devices over MQTT.
 * Subscribe: {topicRoot}/device/+/command/result
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandResultListener {

    private final ObjectMapper objectMapper;
    private final MqttSubscriptions subscriptions;
    private final CommandExecutionRepository executionRepository;

    @PostConstruct
    void register() {
        subscriptions.register("/device/+/command/result",
                MqttTopics.QOS_RELIABLE, this::onCommandResult);
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
}
