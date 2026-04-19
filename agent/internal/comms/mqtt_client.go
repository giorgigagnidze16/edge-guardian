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

// DesiredStateHandler is called when a new desired state manifest is pushed.
type DesiredStateHandler func(manifest *model.DeviceManifest)

// CertResponseHandler is called when a signed certificate is received from the controller.
type CertResponseHandler func(name string, certPEM, caCertPEM []byte)

// MQTTClient manages the MQTT connection for all agent-controller communication.
type MQTTClient struct {
	client              mqtt.Client
	deviceID            string
	topicRoot           string
	store               *storage.Store
	logger              *zap.Logger
	bootstrap           bool
	cmdHandler          CommandHandler
	desiredStateHandler DesiredStateHandler
	certResponseHandler CertResponseHandler
	enrollResponseCh    chan *model.RegisterResponse
	mu                  sync.RWMutex
}

// MQTTConfig holds MQTT connection parameters.
type MQTTConfig struct {
	BrokerURL string
	DeviceID  string
	Username  string
	Password  string
	TopicRoot string
	Store     *storage.Store
	TLS       TLSConfig
	Bootstrap bool
}

// NewMQTTClient creates an MQTT client. Call Connect() to establish the connection.
func NewMQTTClient(cfg MQTTConfig, logger *zap.Logger) (*MQTTClient, error) {
	mc := &MQTTClient{
		deviceID:         cfg.DeviceID,
		topicRoot:        cfg.TopicRoot,
		store:            cfg.Store,
		logger:           logger,
		bootstrap:        cfg.Bootstrap,
		enrollResponseCh: make(chan *model.RegisterResponse, 1),
	}

	opts := mqtt.NewClientOptions().
		AddBroker(cfg.BrokerURL).
		SetClientID(cfg.DeviceID).
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

	if !cfg.TLS.IsZero() {
		tlsCfg, err := cfg.TLS.Build()
		if err != nil {
			return nil, fmt.Errorf("build TLS config: %w", err)
		}
		opts.SetTLSConfig(tlsCfg)
		logger.Info("MQTT client using mutual TLS",
			zap.String("broker", cfg.BrokerURL),
			zap.String("ca_cert", cfg.TLS.CACertPath),
			zap.String("identity_cert", cfg.TLS.IdentityCertPath))
	}

	mc.client = mqtt.NewClient(opts)
	return mc, nil
}

// SetCommandHandler sets the callback for received commands.
func (mc *MQTTClient) SetCommandHandler(handler CommandHandler) {
	mc.mu.Lock()
	defer mc.mu.Unlock()
	mc.cmdHandler = handler
}

// SetDesiredStateHandler sets the callback for desired state pushes.
func (mc *MQTTClient) SetDesiredStateHandler(handler DesiredStateHandler) {
	mc.mu.Lock()
	defer mc.mu.Unlock()
	mc.desiredStateHandler = handler
}

// SetCertResponseHandler sets the callback for certificate responses from the controller.
func (mc *MQTTClient) SetCertResponseHandler(handler CertResponseHandler) {
	mc.mu.Lock()
	defer mc.mu.Unlock()
	mc.certResponseHandler = handler
}

// Connect establishes the MQTT connection.
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

// PublishEnrollRequest sends an enrollment request and waits for the controller response.
func (mc *MQTTClient) PublishEnrollRequest(req *model.EnrollRequest) (*model.RegisterResponse, error) {
	select {
	case <-mc.enrollResponseCh:
	default:
	}

	responseTopic := mc.topic("enroll/response")
	token := mc.client.Subscribe(responseTopic, 1, mc.handleEnrollResponse)
	if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
		return nil, fmt.Errorf("subscribe enroll/response: %w", token.Error())
	}

	payload, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("marshal enroll request: %w", err)
	}

	requestTopic := mc.topic("enroll/request")
	pubToken := mc.client.Publish(requestTopic, 1, false, payload)
	if !pubToken.WaitTimeout(5*time.Second) || pubToken.Error() != nil {
		return nil, fmt.Errorf("publish enroll request: %w", pubToken.Error())
	}

	mc.logger.Info("enrollment request published, waiting for response",
		zap.String("device_id", mc.deviceID))

	select {
	case resp := <-mc.enrollResponseCh:
		mc.client.Unsubscribe(responseTopic)
		return resp, nil
	case <-time.After(15 * time.Second):
		mc.client.Unsubscribe(responseTopic)
		return nil, fmt.Errorf("enrollment response timeout after 15s")
	}
}

func (mc *MQTTClient) handleEnrollResponse(_ mqtt.Client, msg mqtt.Message) {
	var resp model.RegisterResponse
	if err := json.Unmarshal(msg.Payload(), &resp); err != nil {
		mc.logger.Error("unmarshal enroll response", zap.Error(err))
		return
	}

	select {
	case mc.enrollResponseCh <- &resp:
	default:
		mc.logger.Warn("enroll response channel full, dropping response")
	}
}

// PublishHeartbeat sends a heartbeat message. Falls back to offline queue if disconnected.
func (mc *MQTTClient) PublishHeartbeat(msg *model.HeartbeatMessage) error {
	return mc.publishWithQueue(mc.topic("heartbeat"), msg)
}

// RunHeartbeatLoop publishes a heartbeat immediately then every interval until
// ctx is cancelled. The first beat fires straight away so the controller
// transitions the device to ONLINE right after connect instead of waiting a
// full tick (otherwise a fresh agent looks dead until the next interval).
func (mc *MQTTClient) RunHeartbeatLoop(ctx context.Context, interval time.Duration, msgFn func() *model.HeartbeatMessage) {
	publish := func() {
		if err := mc.PublishHeartbeat(msgFn()); err != nil {
			mc.logger.Warn("heartbeat publish failed", zap.Error(err))
		}
	}

	publish()

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			publish()
		}
	}
}

// PublishOTAStatus reports OTA progress to the controller.
func (mc *MQTTClient) PublishOTAStatus(report *model.OTAStatusReport) error {
	return mc.publishWithQueue(mc.topic("ota/status"), report)
}

// PublishCommandResult sends the outcome of a command execution.
func (mc *MQTTClient) PublishCommandResult(result *model.CommandResult) error {
	return mc.publishWithQueue(mc.topic("command/result"), result)
}

// PublishCertRequest sends a certificate signing request to the controller.
func (mc *MQTTClient) PublishCertRequest(name, commonName string, sans []string, csrPEM []byte, reqType, currentSerial string) error {
	msg := map[string]interface{}{
		"deviceId":      mc.deviceID,
		"name":          name,
		"commonName":    commonName,
		"sans":          sans,
		"csrPem":        string(csrPEM),
		"type":          reqType,
		"currentSerial": currentSerial,
	}
	return mc.publishWithQueue(mc.topic("cert/request"), msg)
}

// PublishTelemetry sends a telemetry message, queuing to BoltDB if offline.
func (mc *MQTTClient) PublishTelemetry(status *model.DeviceStatus) error {
	msg := model.TelemetryMessage{
		DeviceID:  mc.deviceID,
		Timestamp: time.Now(),
		Status:    status,
	}
	return mc.publishWithQueue(mc.topic("telemetry"), msg)
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

// DrainOfflineQueue attempts to publish all queued messages.
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
			// IsConnected + Publish is not atomic, but the error path re-queues so no loss.
			if !mc.client.IsConnected() {
				if err := mc.store.EnqueueMessage(msg); err != nil {
					mc.logger.Error("re-queue message", zap.Error(err))
				}
				return
			}

			token := mc.client.Publish(msg.Topic, 1, false, msg.Payload)
			if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
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

// Close disconnects from the MQTT broker.
func (mc *MQTTClient) Close() {
	mc.client.Disconnect(1000)
	mc.logger.Info("MQTT disconnected")
}

func (mc *MQTTClient) topic(suffix string) string {
	return fmt.Sprintf("%s/device/%s/%s", mc.topicRoot, mc.deviceID, suffix)
}

func (mc *MQTTClient) publishWithQueue(topic string, payload interface{}) error {
	data, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal payload: %w", err)
	}

	if !mc.client.IsConnected() {
		return mc.queueMessage(topic, data)
	}

	token := mc.client.Publish(topic, 1, false, data)
	if !token.WaitTimeout(5 * time.Second) {
		return mc.queueMessage(topic, data)
	}
	if token.Error() != nil {
		return mc.queueMessage(topic, data)
	}

	mc.logger.Debug("published", zap.String("topic", topic))
	return nil
}

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

func (mc *MQTTClient) onConnect(client mqtt.Client) {
	if mc.bootstrap {
		mc.logger.Info("MQTT bootstrap connection established, enrollment")
		return
	}
	mc.logger.Info("MQTT connection established, subscribing to topics")

	mc.subscribeToTopic(client, mc.topic("command"), mc.handleCommand)
	mc.subscribeToTopic(client, mc.topic("state/desired"), mc.handleDesiredState)
	mc.subscribeToTopic(client, mc.topic("cert/response"), mc.handleCertResponse)

	go mc.DrainOfflineQueue()
}

func (mc *MQTTClient) subscribeToTopic(client mqtt.Client, topic string, handler mqtt.MessageHandler) {
	token := client.Subscribe(topic, 1, handler)
	if token.WaitTimeout(10*time.Second) && token.Error() == nil {
		mc.logger.Info("subscribed", zap.String("topic", topic))
	} else {
		mc.logger.Error("failed to subscribe",
			zap.String("topic", topic),
			zap.Error(token.Error()),
		)
	}
}

func (mc *MQTTClient) onConnectionLost(_ mqtt.Client, err error) {
	mc.logger.Warn("MQTT connection lost", zap.Error(err))
}

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

func (mc *MQTTClient) handleCertResponse(_ mqtt.Client, msg mqtt.Message) {
	mc.logger.Info("cert response received",
		zap.String("topic", msg.Topic()),
		zap.Int("payload_len", len(msg.Payload())),
	)

	var resp struct {
		Name      string `json:"name"`
		Accepted  bool   `json:"accepted"`
		Message   string `json:"message"`
		CertPem   string `json:"certPem"`
		CaCertPem string `json:"caCertPem"`
	}
	if err := json.Unmarshal(msg.Payload(), &resp); err != nil {
		mc.logger.Error("unmarshal cert response", zap.Error(err))
		return
	}

	if !resp.Accepted {
		mc.logger.Warn("cert request rejected",
			zap.String("name", resp.Name), zap.String("message", resp.Message))
		return
	}

	mc.mu.RLock()
	handler := mc.certResponseHandler
	mc.mu.RUnlock()

	if handler != nil {
		handler(resp.Name, []byte(resp.CertPem), []byte(resp.CaCertPem))
	}
}

func (mc *MQTTClient) handleDesiredState(_ mqtt.Client, msg mqtt.Message) {
	mc.logger.Info("desired state received",
		zap.String("topic", msg.Topic()),
		zap.Int("payload_len", len(msg.Payload())),
	)

	var manifest model.DeviceManifest
	if err := json.Unmarshal(msg.Payload(), &manifest); err != nil {
		mc.logger.Error("unmarshal desired state", zap.Error(err))
		return
	}

	mc.mu.RLock()
	handler := mc.desiredStateHandler
	mc.mu.RUnlock()

	if handler != nil {
		handler(&manifest)
	}
}
