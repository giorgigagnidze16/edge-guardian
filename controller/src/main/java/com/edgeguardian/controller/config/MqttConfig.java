package com.edgeguardian.controller.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the MQTT client for the controller.
 * The controller subscribes to telemetry topics and publishes commands.
 */
@Slf4j
@Configuration
public class MqttConfig {

    @Value("${edgeguardian.controller.mqtt.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${edgeguardian.controller.mqtt.client-id:edgeguardian-controller}")
    private String clientId;

    private MqttClient mqttClient;

    @Bean
    public MqttClient mqttClient() {
        try {
            mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setAutomaticReconnect(true);
            options.setCleanStart(false);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(60);

            mqttClient.connect(options);
            log.info("MQTT client connected to {} as {}", brokerUrl, clientId);
        } catch (MqttException e) {
            log.warn("Failed to connect MQTT client to {} - telemetry will be unavailable: {}",
                brokerUrl, e.getMessage());
            // Return the unconnected client; subscribers will handle the disconnected state.
        }

        return mqttClient;
    }

    @PreDestroy
    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                log.info("MQTT client disconnected");
            } catch (MqttException e) {
                log.warn("Error disconnecting MQTT client: {}", e.getMessage());
            }
        }
    }
}
