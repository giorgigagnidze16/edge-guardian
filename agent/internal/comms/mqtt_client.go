package comms

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
	"github.com/edgeguardian/agent/internal/model"
	"github.com/edgeguardian/agent/internal/storage"
	"go.uber.org/zap"
)

// CommandHandler is called when a command is received via MQTT.
type CommandHandler func(cmd model.Command)

// MQTTClient manages the MQTT connection for telemetry publishing
// and command subscription. It uses BoltDB for offline buffering.
type MQTTClient struct {
	client     mqtt.Client
	deviceID   string
	topicRoot  string
	store      *storage.Store
	logger     *zap.Logger
	cmdHandler CommandHandler
	mu         sync.RWMutex
}

// MQTTConfig holds MQTT connection parameters.
type MQTTConfig struct {
	BrokerURL string
	DeviceID  string
	Username  string
	Password  string
	TopicRoot string
	Store     *storage.Store
}

// NewMQTTClient creates an MQTT client. Call Connect() to establish the connection.
func NewMQTTClient(cfg MQTTConfig, logger *zap.Logger) *MQTTClient {
	mc := &MQTTClient{
		deviceID:  cfg.DeviceID,
		topicRoot: cfg.TopicRoot,
		store:     cfg.Store,
		logger:    logger,
	}

	opts := mqtt.NewClientOptions().
		AddBroker(cfg.BrokerURL).
		SetClientID("eg-agent-" + cfg.DeviceID).
		SetAutoReconnect(true).
		SetMaxReconnectInterval(30 * time.Second).
		SetKeepAlive(60 * time.Second).
		SetCleanSession(false).
		SetConnectionLostHandler(mc.onConnectionLost).
		SetOnConnectHandler(mc.onConnect)

	if cfg.Username != "" {
		opts.SetUsername(cfg.Username)
		opts.SetPassword(cfg.Password)
	}

	mc.client = mqtt.NewClient(opts)
	return mc
}

// SetCommandHandler sets the callback for received commands.
func (mc *MQTTClient) SetCommandHandler(handler CommandHandler) {
	mc.mu.Lock()
	defer mc.mu.Unlock()
	mc.cmdHandler = handler
}

// Connect establishes the MQTT connection. Non-blocking: returns error only
// if the initial connection attempt fails within the timeout.
func (mc *MQTTClient) Connect(timeout time.Duration) error {
	token := mc.client.Connect()
	if !token.WaitTimeout(timeout) {
		return fmt.Errorf("MQTT connect timeout after %v", timeout)
	}
	if token.Error() != nil {
		return fmt.Errorf("MQTT connect: %w", token.Error())
	}
	mc.logger.Info("MQTT connected", zap.String("device_id", mc.deviceID))
	return nil
}

// PublishTelemetry sends a telemetry message. If the broker is unreachable,
// the message is queued in BoltDB for later delivery.
func (mc *MQTTClient) PublishTelemetry(status *model.DeviceStatus) error {
	msg := model.TelemetryMessage{
		DeviceID:  mc.deviceID,
		Timestamp: time.Now(),
		Status:    status,
	}

	payload, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal telemetry: %w", err)
	}

	topic := mc.telemetryTopic()

	if !mc.client.IsConnected() {
		return mc.queueMessage(topic, payload)
	}

	token := mc.client.Publish(topic, 1, false, payload)
	if !token.WaitTimeout(5 * time.Second) {
		return mc.queueMessage(topic, payload)
	}
	if token.Error() != nil {
		return mc.queueMessage(topic, payload)
	}

	mc.logger.Debug("telemetry published", zap.String("topic", topic))
	return nil
}

// DrainOfflineQueue attempts to publish all queued messages. Called after reconnection.
func (mc *MQTTClient) DrainOfflineQueue() {
	if mc.store == nil {
		return
	}

	for {
		msgs, err := mc.store.DequeueMessages(10)
		if err != nil {
			mc.logger.Error("drain offline queue: dequeue", zap.Error(err))
			return
		}
		if len(msgs) == 0 {
			return
		}

		for _, msg := range msgs {
			if !mc.client.IsConnected() {
				// Re-queue if we lost connection during drain.
				if err := mc.store.EnqueueMessage(msg); err != nil {
					mc.logger.Error("re-queue message", zap.Error(err))
				}
				return
			}

			token := mc.client.Publish(msg.Topic, 1, false, msg.Payload)
			if !token.WaitTimeout(5 * time.Second) || token.Error() != nil {
				// Re-queue on failure.
				if err := mc.store.EnqueueMessage(msg); err != nil {
					mc.logger.Error("re-queue message", zap.Error(err))
				}
				return
			}
			mc.logger.Debug("drained queued message",
				zap.String("topic", msg.Topic),
				zap.Time("queued_at", msg.QueuedAt),
			)
		}
	}
}

// RunTelemetryLoop publishes telemetry at the given interval until ctx is cancelled.
func (mc *MQTTClient) RunTelemetryLoop(ctx context.Context, interval time.Duration, statusFn func() *model.DeviceStatus) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			status := statusFn()
			if err := mc.PublishTelemetry(status); err != nil {
				mc.logger.Warn("telemetry publish failed", zap.Error(err))
			}
		}
	}
}

// Close disconnects from the MQTT broker.
func (mc *MQTTClient) Close() {
	mc.client.Disconnect(1000)
	mc.logger.Info("MQTT disconnected")
}

// telemetryTopic returns the topic for this device's telemetry.
func (mc *MQTTClient) telemetryTopic() string {
	return fmt.Sprintf("%s/device/%s/telemetry", mc.topicRoot, mc.deviceID)
}

// commandTopic returns the topic for this device's commands.
func (mc *MQTTClient) commandTopic() string {
	return fmt.Sprintf("%s/device/%s/command", mc.topicRoot, mc.deviceID)
}

// queueMessage stores a message in BoltDB for later delivery.
func (mc *MQTTClient) queueMessage(topic string, payload []byte) error {
	if mc.store == nil {
		return fmt.Errorf("no store configured for offline queueing")
	}
	mc.logger.Debug("queueing message for offline delivery", zap.String("topic", topic))
	return mc.store.EnqueueMessage(&storage.QueuedMessage{
		Topic:    topic,
		Payload:  payload,
		QueuedAt: time.Now(),
	})
}

// onConnect is called when the MQTT connection is established or re-established.
func (mc *MQTTClient) onConnect(client mqtt.Client) {
	mc.logger.Info("MQTT connection established, subscribing to commands")

	// Subscribe to command topic.
	topic := mc.commandTopic()
	token := client.Subscribe(topic, 1, mc.handleCommand)
	if token.WaitTimeout(10*time.Second) && token.Error() == nil {
		mc.logger.Info("subscribed to commands", zap.String("topic", topic))
	} else {
		mc.logger.Error("failed to subscribe to commands",
			zap.String("topic", topic),
			zap.Error(token.Error()),
		)
	}

	// Drain any messages that were queued while offline.
	go mc.DrainOfflineQueue()
}

// onConnectionLost is called when the MQTT connection drops.
func (mc *MQTTClient) onConnectionLost(_ mqtt.Client, err error) {
	mc.logger.Warn("MQTT connection lost", zap.Error(err))
}

// handleCommand processes incoming MQTT command messages.
func (mc *MQTTClient) handleCommand(_ mqtt.Client, msg mqtt.Message) {
	mc.logger.Info("command received",
		zap.String("topic", msg.Topic()),
		zap.Int("payload_len", len(msg.Payload())),
	)

	var cmdMsg model.CommandMessage
	if err := json.Unmarshal(msg.Payload(), &cmdMsg); err != nil {
		mc.logger.Error("unmarshal command", zap.Error(err))
		return
	}

	mc.mu.RLock()
	handler := mc.cmdHandler
	mc.mu.RUnlock()

	if handler != nil {
		handler(cmdMsg.Command)
	}
}
