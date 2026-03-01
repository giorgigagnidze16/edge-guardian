//go:build integration

package comms

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
	"github.com/edgeguardian/agent/internal/model"
	"github.com/edgeguardian/agent/internal/storage"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
	"go.uber.org/zap"
)

func mqttTestLogger() *zap.Logger {
	logger, _ := zap.NewDevelopment()
	return logger
}

// brokerURL returns the MQTT broker address. If MQTT_BROKER_URL is set
// (e.g. inside the Docker integration test container with local mosquitto),
// it uses that. Otherwise it starts a Mosquitto container via testcontainers.
func brokerURL(t *testing.T) string {
	t.Helper()
	if url := os.Getenv("MQTT_BROKER_URL"); url != "" {
		return url
	}
	return setupMosquitto(t)
}

// setupMosquitto starts a Mosquitto MQTT broker in Docker via testcontainers.
func setupMosquitto(t *testing.T) string {
	t.Helper()
	ctx := context.Background()

	req := testcontainers.ContainerRequest{
		Image:        "eclipse-mosquitto:2",
		ExposedPorts: []string{"1883/tcp"},
		WaitingFor:   wait.ForListeningPort("1883/tcp").WithStartupTimeout(30 * time.Second),
		Entrypoint:   []string{"sh", "-c", "echo 'listener 1883\nallow_anonymous true' > /tmp/mosquitto.conf && mosquitto -c /tmp/mosquitto.conf"},
	}

	container, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	})
	if err != nil {
		t.Fatalf("start mosquitto container: %v", err)
	}
	t.Cleanup(func() { container.Terminate(ctx) })

	host, err := container.Host(ctx)
	if err != nil {
		t.Fatalf("get container host: %v", err)
	}
	port, err := container.MappedPort(ctx, "1883")
	if err != nil {
		t.Fatalf("get mapped port: %v", err)
	}

	return fmt.Sprintf("tcp://%s:%s", host, port.Port())
}

// openTestStore creates a BoltDB store in a temp directory.
func openTestStore(t *testing.T) *storage.Store {
	t.Helper()
	dir := t.TempDir()
	store, err := storage.Open(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("open test store: %v", err)
	}
	t.Cleanup(func() { store.Close() })
	return store
}

func TestMQTT_ConnectAndDisconnect(t *testing.T) {
	url := brokerURL(t)

	mc := NewMQTTClient(MQTTConfig{
		BrokerURL: url,
		DeviceID:  "test-device-1",
		TopicRoot: "test",
	}, mqttTestLogger())

	if err := mc.Connect(10 * time.Second); err != nil {
		t.Fatalf("Connect: %v", err)
	}

	if !mc.client.IsConnected() {
		t.Fatal("expected client to be connected")
	}

	mc.Close()

	time.Sleep(100 * time.Millisecond)
	if mc.client.IsConnected() {
		t.Fatal("expected client to be disconnected after Close()")
	}
}

func TestMQTT_ConnectFailure_BadURL(t *testing.T) {
	mc := NewMQTTClient(MQTTConfig{
		BrokerURL: "tcp://127.0.0.1:1",
		DeviceID:  "fail-device",
		TopicRoot: "test",
	}, mqttTestLogger())

	err := mc.Connect(2 * time.Second)
	if err == nil {
		mc.Close()
		t.Fatal("expected connection error for bad broker URL")
	}
}

func TestMQTT_PublishTelemetry(t *testing.T) {
	url := brokerURL(t)
	store := openTestStore(t)

	mc := NewMQTTClient(MQTTConfig{
		BrokerURL: url,
		DeviceID:  "pub-device",
		TopicRoot: "test",
		Store:     store,
	}, mqttTestLogger())

	if err := mc.Connect(10 * time.Second); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	defer mc.Close()

	received := make(chan []byte, 1)
	topic := "test/device/pub-device/telemetry"

	token := mc.client.Subscribe(topic, 1, func(_ mqtt.Client, msg mqtt.Message) {
		received <- msg.Payload()
	})
	if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
		t.Fatalf("subscribe: %v", token.Error())
	}

	status := &model.DeviceStatus{
		State:           "online",
		CPUUsagePercent: 33.3,
		UptimeSeconds:   7200,
	}

	if err := mc.PublishTelemetry(status); err != nil {
		t.Fatalf("PublishTelemetry: %v", err)
	}

	select {
	case payload := <-received:
		var msg model.TelemetryMessage
		if err := json.Unmarshal(payload, &msg); err != nil {
			t.Fatalf("unmarshal telemetry: %v", err)
		}
		if msg.DeviceID != "pub-device" {
			t.Fatalf("expected deviceId=pub-device, got %q", msg.DeviceID)
		}
		if msg.Status.CPUUsagePercent != 33.3 {
			t.Fatalf("expected CPU=33.3, got %f", msg.Status.CPUUsagePercent)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for telemetry message")
	}
}

func TestMQTT_SubscribeCommands(t *testing.T) {
	url := brokerURL(t)

	mc := NewMQTTClient(MQTTConfig{
		BrokerURL: url,
		DeviceID:  "cmd-device",
		TopicRoot: "test",
	}, mqttTestLogger())

	var receivedCmd model.Command
	var mu sync.Mutex
	cmdReceived := make(chan struct{}, 1)

	mc.SetCommandHandler(func(cmd model.Command) {
		mu.Lock()
		receivedCmd = cmd
		mu.Unlock()
		cmdReceived <- struct{}{}
	})

	if err := mc.Connect(10 * time.Second); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	defer mc.Close()

	time.Sleep(500 * time.Millisecond)

	cmdMsg := model.CommandMessage{
		Command: model.Command{
			ID:   "cmd-42",
			Type: "restart",
			Params: map[string]string{
				"reason": "config update",
			},
		},
	}
	payload, _ := json.Marshal(cmdMsg)
	topic := "test/device/cmd-device/command"
	token := mc.client.Publish(topic, 1, false, payload)
	if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
		t.Fatalf("publish command: %v", token.Error())
	}

	select {
	case <-cmdReceived:
		mu.Lock()
		defer mu.Unlock()
		if receivedCmd.ID != "cmd-42" {
			t.Fatalf("expected cmd ID=cmd-42, got %q", receivedCmd.ID)
		}
		if receivedCmd.Type != "restart" {
			t.Fatalf("expected cmd Type=restart, got %q", receivedCmd.Type)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for command")
	}
}

func TestMQTT_OfflineQueue(t *testing.T) {
	store := openTestStore(t)

	mc := NewMQTTClient(MQTTConfig{
		BrokerURL: "tcp://127.0.0.1:1",
		DeviceID:  "offline-device",
		TopicRoot: "test",
		Store:     store,
	}, mqttTestLogger())

	status := &model.DeviceStatus{State: "online", CPUUsagePercent: 10.0}
	if err := mc.PublishTelemetry(status); err != nil {
		t.Fatalf("PublishTelemetry (offline): %v", err)
	}

	depth, err := store.QueueDepth()
	if err != nil {
		t.Fatalf("QueueDepth: %v", err)
	}
	if depth != 1 {
		t.Fatalf("expected queue depth=1, got %d", depth)
	}

	msgs, err := store.DequeueMessages(1)
	if err != nil {
		t.Fatalf("DequeueMessages: %v", err)
	}
	if msgs[0].Topic != "test/device/offline-device/telemetry" {
		t.Fatalf("expected correct topic, got %q", msgs[0].Topic)
	}

	var telemetry model.TelemetryMessage
	if err := json.Unmarshal(msgs[0].Payload, &telemetry); err != nil {
		t.Fatalf("unmarshal queued payload: %v", err)
	}
	if telemetry.DeviceID != "offline-device" {
		t.Fatalf("expected deviceId=offline-device, got %q", telemetry.DeviceID)
	}
}

func TestMQTT_PublishTelemetry_NoStore_ReturnsError(t *testing.T) {
	mc := NewMQTTClient(MQTTConfig{
		BrokerURL: "tcp://127.0.0.1:1",
		DeviceID:  "no-store-device",
		TopicRoot: "test",
	}, mqttTestLogger())

	status := &model.DeviceStatus{State: "online"}
	err := mc.PublishTelemetry(status)
	if err == nil {
		t.Fatal("expected error when offline with no store")
	}
}

func TestMQTT_PubSub_EndToEnd(t *testing.T) {
	url := brokerURL(t)

	pubOpts := mqtt.NewClientOptions().
		AddBroker(url).
		SetClientID("test-pub").
		SetCleanSession(true)
	pub := mqtt.NewClient(pubOpts)
	token := pub.Connect()
	if !token.WaitTimeout(10*time.Second) || token.Error() != nil {
		t.Fatalf("pub connect: %v", token.Error())
	}
	defer pub.Disconnect(250)

	subOpts := mqtt.NewClientOptions().
		AddBroker(url).
		SetClientID("test-sub").
		SetCleanSession(true)
	sub := mqtt.NewClient(subOpts)
	token = sub.Connect()
	if !token.WaitTimeout(10*time.Second) || token.Error() != nil {
		t.Fatalf("sub connect: %v", token.Error())
	}
	defer sub.Disconnect(250)

	received := make(chan string, 1)
	topic := "test/e2e/messages"
	token = sub.Subscribe(topic, 1, func(_ mqtt.Client, msg mqtt.Message) {
		received <- string(msg.Payload())
	})
	if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
		t.Fatalf("subscribe: %v", token.Error())
	}

	token = pub.Publish(topic, 1, false, "hello from test")
	if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
		t.Fatalf("publish: %v", token.Error())
	}

	select {
	case msg := <-received:
		if msg != "hello from test" {
			t.Fatalf("expected 'hello from test', got %q", msg)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for pub/sub message")
	}
}

func TestMQTT_TelemetryLoop(t *testing.T) {
	url := brokerURL(t)
	store := openTestStore(t)

	mc := NewMQTTClient(MQTTConfig{
		BrokerURL: url,
		DeviceID:  "loop-device",
		TopicRoot: "test",
		Store:     store,
	}, mqttTestLogger())

	if err := mc.Connect(10 * time.Second); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	defer mc.Close()

	received := make(chan struct{}, 5)
	topic := "test/device/loop-device/telemetry"
	token := mc.client.Subscribe(topic, 1, func(_ mqtt.Client, msg mqtt.Message) {
		received <- struct{}{}
	})
	if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
		t.Fatalf("subscribe: %v", token.Error())
	}

	ctx, cancel := context.WithCancel(context.Background())
	go mc.RunTelemetryLoop(ctx, 200*time.Millisecond, func() *model.DeviceStatus {
		return &model.DeviceStatus{State: "online", CPUUsagePercent: 10.0}
	})

	count := 0
	timeout := time.After(3 * time.Second)
	for count < 2 {
		select {
		case <-received:
			count++
		case <-timeout:
			t.Fatalf("timed out, only received %d telemetry messages", count)
		}
	}

	cancel()
}

func TestMQTT_DrainOfflineQueue(t *testing.T) {
	url := brokerURL(t)
	store := openTestStore(t)

	msg := &storage.QueuedMessage{
		Topic:    "test/device/drain-device/telemetry",
		Payload:  []byte(`{"deviceId":"drain-device","status":{"state":"online"}}`),
		QueuedAt: time.Now(),
	}
	if err := store.EnqueueMessage(msg); err != nil {
		t.Fatalf("enqueue: %v", err)
	}

	subOpts := mqtt.NewClientOptions().
		AddBroker(url).
		SetClientID("drain-sub").
		SetCleanSession(true)
	sub := mqtt.NewClient(subOpts)
	token := sub.Connect()
	if !token.WaitTimeout(10*time.Second) || token.Error() != nil {
		t.Fatalf("sub connect: %v", token.Error())
	}
	defer sub.Disconnect(250)

	received := make(chan []byte, 1)
	token = sub.Subscribe("test/device/drain-device/telemetry", 1, func(_ mqtt.Client, m mqtt.Message) {
		received <- m.Payload()
	})
	if !token.WaitTimeout(5*time.Second) || token.Error() != nil {
		t.Fatalf("subscribe: %v", token.Error())
	}

	mc := NewMQTTClient(MQTTConfig{
		BrokerURL: url,
		DeviceID:  "drain-device",
		TopicRoot: "test",
		Store:     store,
	}, mqttTestLogger())

	if err := mc.Connect(10 * time.Second); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	defer mc.Close()

	select {
	case <-received:
		// Drained message arrived.
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for drained message")
	}

	time.Sleep(500 * time.Millisecond)
	depth, _ := store.QueueDepth()
	if depth != 0 {
		t.Fatalf("expected queue depth=0 after drain, got %d", depth)
	}
}

func TestMQTT_TopicFormat(t *testing.T) {
	mc := &MQTTClient{
		deviceID:  "sensor-42",
		topicRoot: "edgeguardian",
	}

	telTopic := mc.telemetryTopic()
	if telTopic != "edgeguardian/device/sensor-42/telemetry" {
		t.Fatalf("telemetry topic: got %q", telTopic)
	}

	cmdTopic := mc.commandTopic()
	if cmdTopic != "edgeguardian/device/sensor-42/command" {
		t.Fatalf("command topic: got %q", cmdTopic)
	}
}
