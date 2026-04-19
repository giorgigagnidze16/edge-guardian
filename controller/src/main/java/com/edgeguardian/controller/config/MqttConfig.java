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

import java.time.Duration;
import java.util.concurrent.Executors;

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

            connectWithBackoff(options, props);
        } catch (MqttException e) {
            log.warn("Failed to create MQTT client to {}: {}",
                    props.brokerUrl(), e.getMessage());
        }
        return mqttClient;
    }

    /**
     * Paho's {@code automaticReconnect} only engages after a successful first
     * connect. If the broker isn't ready when the controller starts (ordering
     * race between pods, or EMQX still booting), a single {@code connect()}
     * that fails leaves the client permanently idle. Retry in the background
     * until the first connect succeeds, so the controller is resilient to any
     * startup order.
     */
    private void connectWithBackoff(MqttConnectionOptions options, MqttProperties props) {
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mqtt-initial-connect");
            t.setDaemon(true);
            return t;
        }).submit(() -> {
            Duration backoff = Duration.ofSeconds(2);
            Duration max = Duration.ofSeconds(30);
            for (int attempt = 1; ; attempt++) {
                try {
                    mqttClient.connect(options);
                    log.info("MQTT connected to {} as {} (attempt={}, sessionExpiry={}, keepAlive={})",
                            props.brokerUrl(), props.clientId(), attempt,
                            props.sessionExpiry(), props.keepAlive());
                    return;
                } catch (MqttException e) {
                    log.warn("MQTT initial connect attempt {} to {} failed: {} - retry in {}",
                            attempt, props.brokerUrl(), e.getMessage(), backoff);
                    try { Thread.sleep(backoff.toMillis()); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = backoff.multipliedBy(2);
                    if (backoff.compareTo(max) > 0) backoff = max;
                }
            }
        });
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
     * Replays every registered subscription on every successful connect —
     * both the first one (initial connect may complete after listeners have
     * already called register(), so their subscribe attempts were deferred)
     * and every reconnect after a broker outage. {@link MqttSubscriptions}
     * subscribe is idempotent, so replaying a binding that's already active
     * is a no-op at the broker.
     */
    private record ResubscribeCallback(ObjectProvider<MqttSubscriptions> subs) implements MqttCallback {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
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
