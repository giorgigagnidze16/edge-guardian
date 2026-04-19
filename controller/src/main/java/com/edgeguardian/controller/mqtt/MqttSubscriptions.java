package com.edgeguardian.controller.mqtt;

import com.edgeguardian.controller.config.MqttProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry of MQTT subscriptions. Listeners call {@link #register} once
 * at startup; the registry subscribes immediately if connected and replays
 * every binding on reconnect. Keeps listener classes free of Paho boilerplate
 * and guarantees subscriptions survive broker outages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttSubscriptions {

    private final MqttClient client;
    private final MqttProperties props;

    private final CopyOnWriteArrayList<Binding> bindings = new CopyOnWriteArrayList<>();

    public void register(String topicSuffix, int qos, IMqttMessageListener handler) {
        Binding b = new Binding(topicSuffix, qos, handler);
        bindings.add(b);
        subscribe(b);
    }

    /**
     * Re-subscribe every registered binding; called by the reconnect callback.
     */
    public void resubscribeAll() {
        log.info("Re-subscribing {} MQTT binding(s) after reconnect", bindings.size());
        for (Binding b : bindings) {
            subscribe(b);
        }
    }

    private void subscribe(Binding b) {
        if (!client.isConnected()) {
            log.debug("MQTT not connected; binding {} deferred until (re)connect", b.topicSuffix);
            return;
        }
        String topic = props.topicRoot() + b.topicSuffix;
        try {
            client.subscribe(
                    new MqttSubscription[]{new MqttSubscription(topic, b.qos)},
                    new IMqttMessageListener[]{b.handler});
            log.info("Subscribed to {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to {}: {}", topic, e.getMessage());
        }
    }

    public record Binding(String topicSuffix, int qos, IMqttMessageListener handler) {
    }
}
