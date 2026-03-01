package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultConfig_HasCorrectDefaults(t *testing.T) {
	cfg := DefaultConfig()

	hostname, _ := os.Hostname()
	if cfg.DeviceID != hostname {
		t.Fatalf("expected DeviceID=%q, got %q", hostname, cfg.DeviceID)
	}
	if cfg.ControllerAddress != "localhost" {
		t.Fatalf("expected ControllerAddress=localhost, got %q", cfg.ControllerAddress)
	}
	if cfg.ControllerPort != 8443 {
		t.Fatalf("expected ControllerPort=8443, got %d", cfg.ControllerPort)
	}
	if cfg.ReconcileInterval != 30 {
		t.Fatalf("expected ReconcileInterval=30, got %d", cfg.ReconcileInterval)
	}
	if cfg.LogLevel != "info" {
		t.Fatalf("expected LogLevel=info, got %q", cfg.LogLevel)
	}
	if cfg.DataDir != DefaultDataDir {
		t.Fatalf("expected DataDir=%q, got %q", DefaultDataDir, cfg.DataDir)
	}
	if cfg.MQTT.BrokerURL != "tcp://localhost:1883" {
		t.Fatalf("expected MQTT.BrokerURL=tcp://localhost:1883, got %q", cfg.MQTT.BrokerURL)
	}
	if cfg.MQTT.TopicRoot != "edgeguardian" {
		t.Fatalf("expected MQTT.TopicRoot=edgeguardian, got %q", cfg.MQTT.TopicRoot)
	}
	if cfg.Health.DiskPath != DefaultDiskPath {
		t.Fatalf("expected Health.DiskPath=%q, got %q", DefaultDiskPath, cfg.Health.DiskPath)
	}
	if cfg.Labels == nil {
		t.Fatal("expected Labels to be non-nil empty map")
	}
}

func TestLoadConfig_ValidYAML(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.yaml")

	yaml := `device_id: sensor-42
controller_address: 10.0.0.1
controller_port: 9090
reconcile_interval_seconds: 60
log_level: debug
data_dir: /tmp/eg-data
labels:
  zone: warehouse-a
  tier: edge
mqtt:
  broker_url: tcp://mqtt.example.com:1883
  username: agent
  password: secret
  topic_root: myroot
health:
  disk_path: /mnt/data
`
	if err := os.WriteFile(path, []byte(yaml), 0644); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	if cfg.DeviceID != "sensor-42" {
		t.Fatalf("DeviceID: got %q", cfg.DeviceID)
	}
	if cfg.ControllerAddress != "10.0.0.1" {
		t.Fatalf("ControllerAddress: got %q", cfg.ControllerAddress)
	}
	if cfg.ControllerPort != 9090 {
		t.Fatalf("ControllerPort: got %d", cfg.ControllerPort)
	}
	if cfg.ReconcileInterval != 60 {
		t.Fatalf("ReconcileInterval: got %d", cfg.ReconcileInterval)
	}
	if cfg.LogLevel != "debug" {
		t.Fatalf("LogLevel: got %q", cfg.LogLevel)
	}
	if cfg.DataDir != "/tmp/eg-data" {
		t.Fatalf("DataDir: got %q", cfg.DataDir)
	}
	if cfg.Labels["zone"] != "warehouse-a" {
		t.Fatalf("Labels[zone]: got %q", cfg.Labels["zone"])
	}
	if cfg.MQTT.BrokerURL != "tcp://mqtt.example.com:1883" {
		t.Fatalf("MQTT.BrokerURL: got %q", cfg.MQTT.BrokerURL)
	}
	if cfg.MQTT.Username != "agent" {
		t.Fatalf("MQTT.Username: got %q", cfg.MQTT.Username)
	}
	if cfg.Health.DiskPath != "/mnt/data" {
		t.Fatalf("Health.DiskPath: got %q", cfg.Health.DiskPath)
	}
}

func TestLoadConfig_FileNotFound(t *testing.T) {
	_, err := Load("/nonexistent/path/agent.yaml")
	if err == nil {
		t.Fatal("expected error for non-existent file")
	}
}

func TestLoadConfig_InvalidYAML(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "bad.yaml")

	if err := os.WriteFile(path, []byte("{{{{not yaml at all::::"), 0644); err != nil {
		t.Fatal(err)
	}

	_, err := Load(path)
	if err == nil {
		t.Fatal("expected error for invalid YAML")
	}
}

func TestLoadConfig_PartialYAML_FillsDefaults(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "partial.yaml")

	yaml := `device_id: partial-device
log_level: warn
`
	if err := os.WriteFile(path, []byte(yaml), 0644); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	if cfg.DeviceID != "partial-device" {
		t.Fatalf("DeviceID: got %q", cfg.DeviceID)
	}
	if cfg.LogLevel != "warn" {
		t.Fatalf("LogLevel: got %q", cfg.LogLevel)
	}
	// Defaults should fill the rest.
	if cfg.ControllerAddress != "localhost" {
		t.Fatalf("ControllerAddress should default to localhost, got %q", cfg.ControllerAddress)
	}
	if cfg.ControllerPort != 8443 {
		t.Fatalf("ControllerPort should default to 8443, got %d", cfg.ControllerPort)
	}
	if cfg.ReconcileInterval != 30 {
		t.Fatalf("ReconcileInterval should default to 30, got %d", cfg.ReconcileInterval)
	}
	if cfg.MQTT.BrokerURL != "tcp://localhost:1883" {
		t.Fatalf("MQTT.BrokerURL should default, got %q", cfg.MQTT.BrokerURL)
	}
	if cfg.Health.DiskPath != DefaultDiskPath {
		t.Fatalf("Health.DiskPath should default, got %q", cfg.Health.DiskPath)
	}
}
