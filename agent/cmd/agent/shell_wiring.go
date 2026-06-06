package main

import (
	"encoding/json"
	"time"

	"github.com/edgeguardian/agent/internal/comms"
	"github.com/edgeguardian/agent/internal/shell"
	"go.uber.org/zap"
)

// Defense-in-depth limits enforced agent-side even if the controller vanishes.
const (
	shellIdleTimeout = 15 * time.Minute
	shellMaxDuration = 60 * time.Minute
	shellMaxSessions = 2
	shellQoS         = 1
)

// shellTransport bridges the pure shell.Manager to MQTT: it routes open/in/ctl
// topics into the manager and publishes out/event back, implementing
// shell.Publisher.
type shellTransport struct {
	mqtt    *comms.MQTTClient
	manager *shell.Manager
	logger  *zap.Logger
}

// wireShell constructs the shell manager + MQTT transport and registers the
// shell-open handler. Returns the manager so the caller can CloseAll on shutdown.
func wireShell(mqttClient *comms.MQTTClient, logger *zap.Logger) *shell.Manager {
	t := &shellTransport{mqtt: mqttClient, logger: logger}
	t.manager = shell.NewManager(t, shell.Options{
		IdleTimeout: shellIdleTimeout,
		MaxDuration: shellMaxDuration,
		MaxSessions: shellMaxSessions,
		Logger:      logger,
	})
	mqttClient.SetShellOpenHandler(t.handleOpen)
	return t.manager
}

func (t *shellTransport) PublishShellOutput(sessionID string, data []byte) error {
	return t.mqtt.PublishRaw("shell/"+sessionID+"/out", shellQoS, data)
}

func (t *shellTransport) PublishShellEvent(ev shell.Event) error {
	payload, err := json.Marshal(ev)
	if err != nil {
		return err
	}
	pubErr := t.mqtt.PublishRaw("shell/event", shellQoS, payload)
	if ev.Type == "exited" || ev.Type == "error" {
		_ = t.mqtt.Unsubscribe("shell/" + ev.SessionID + "/in")
		_ = t.mqtt.Unsubscribe("shell/" + ev.SessionID + "/ctl")
	}
	return pubErr
}

func (t *shellTransport) handleOpen(payload []byte) {
	var req shell.OpenRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		t.logger.Error("invalid shell open request", zap.Error(err))
		return
	}
	if req.SessionID == "" {
		t.logger.Warn("shell open request missing session id")
		return
	}
	sid := req.SessionID

	// Subscribe to per-session input/control before opening so nothing is missed.
	if err := t.mqtt.Subscribe("shell/"+sid+"/in", shellQoS, func(p []byte) {
		t.manager.Input(sid, p)
	}); err != nil {
		t.logger.Error("subscribe shell input failed", zap.String("session", sid), zap.Error(err))
		return
	}
	if err := t.mqtt.Subscribe("shell/"+sid+"/ctl", shellQoS, func(p []byte) {
		var ctl shell.Control
		if err := json.Unmarshal(p, &ctl); err != nil {
			t.logger.Warn("invalid shell control message", zap.String("session", sid), zap.Error(err))
			return
		}
		t.manager.Control(sid, ctl)
	}); err != nil {
		t.logger.Error("subscribe shell control failed", zap.String("session", sid), zap.Error(err))
		_ = t.mqtt.Unsubscribe("shell/" + sid + "/in")
		return
	}

	t.manager.Open(req)
}
