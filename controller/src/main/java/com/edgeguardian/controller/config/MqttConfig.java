package com.edgeguardian.controller.config;

import com.edgeguardian.controller.mqtt.MqttSubscriptions;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQTT client bean wired from {@link MqttProperties}. A non-zero session
 * expiry keeps subscriptions alive across normal broker restarts; the
 * reconnect callback delegates to {@link MqttSubscriptions} so even if the
 * broker loses state the controller re-registers every binding on reconnect.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {

    private MqttClient mqttClient;

    @Bean
    public MqttClient mqttClient(MqttProperties props,
                                 ObjectProvider<MqttSubscriptions> subscriptions) {
        try {
            mqttClient = new MqttClient(props.brokerUrl(), props.clientId(), new MemoryPersistence());
            mqttClient.setCallback(new ResubscribeCallback(subscriptions));

            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setAutomaticReconnect(true);
            options.setCleanStart(false);
            options.setSessionExpiryInterval(props.sessionExpiry().toSeconds());
            options.setConnectionTimeout((int) props.connectTimeout().toSeconds());
            options.setKeepAliveInterval((int) props.keepAlive().toSeconds());
            if (props.username() != null && !props.username().isBlank()) {
                options.setUserName(props.username());
            }
            if (props.password() != null && !props.password().isBlank()) {
                options.setPassword(props.password().getBytes());
            }

            mqttClient.connect(options);
            log.info("MQTT connected to {} as {} (sessionExpiry={}, keepAlive={})",
                    props.brokerUrl(), props.clientId(),
                    props.sessionExpiry(), props.keepAlive());
        } catch (MqttException e) {
            log.warn("Failed to connect MQTT client to {} - device-plane unavailable: {}",
                    props.brokerUrl(), e.getMessage());
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

    /**
     * On successful reconnect, replays every registered subscription. Startup
     * (reconnect=false) is a no-op — listener @PostConstruct handlers will
     * call {@link MqttSubscriptions#register} to bind their topics.
     */
    private record ResubscribeCallback(ObjectProvider<MqttSubscriptions> subs) implements MqttCallback {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            if (!reconnect) return;
            MqttSubscriptions s = subs.getIfAvailable();
            if (s != null) s.resubscribeAll();
        }

        @Override
        public void disconnected(MqttDisconnectResponse r) {
            log.warn("MQTT disconnected: {}", r.getReasonString());
        }

        @Override
        public void mqttErrorOccurred(MqttException e) {
            log.warn("MQTT error: {}", e.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage m) { /* per-subscription */ }

        @Override
        public void deliveryComplete(IMqttToken token) { /* no-op */ }

        @Override
        public void authPacketArrived(int reasonCode,
                                      org.eclipse.paho.mqttv5.common.packet.MqttProperties p) { /* no-op */ }
    }
}
