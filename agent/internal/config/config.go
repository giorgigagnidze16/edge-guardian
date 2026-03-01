package config

import (
	"os"

	"gopkg.in/yaml.v3"
)

// Config holds the agent configuration.
type Config struct {
	DeviceID          string            `yaml:"device_id"`
	ControllerAddress string            `yaml:"controller_address"`
	ControllerPort    int               `yaml:"controller_port"`
	Labels            map[string]string `yaml:"labels"`
	ReconcileInterval int               `yaml:"reconcile_interval_seconds"`
	LogLevel          string            `yaml:"log_level"`
	DataDir           string            `yaml:"data_dir"`

	// MQTT configuration
	MQTT MQTTConfig `yaml:"mqtt"`

	// Health monitoring configuration
	Health HealthConfig `yaml:"health"`
}

// MQTTConfig holds MQTT broker connection settings.
type MQTTConfig struct {
	BrokerURL string `yaml:"broker_url"`
	Username  string `yaml:"username"`
	Password  string `yaml:"password"`
	TopicRoot string `yaml:"topic_root"`
}

// HealthConfig holds health monitoring settings.
type HealthConfig struct {
	// DiskPath is the filesystem path to monitor for disk usage.
	// Linux/macOS default: "/", Windows default: "C:\".
	// Override to monitor a specific partition or drive.
	DiskPath string `yaml:"disk_path"`
}

// DefaultConfig returns a config with sensible defaults.
func DefaultConfig() *Config {
	hostname, _ := os.Hostname()
	return &Config{
		DeviceID:          hostname,
		ControllerAddress: "localhost",
		ControllerPort:    8443,
		Labels:            map[string]string{},
		ReconcileInterval: 30,
		LogLevel:          "info",
		DataDir:           DefaultDataDir,
		MQTT: MQTTConfig{
			BrokerURL: "tcp://localhost:1883",
			TopicRoot: "edgeguardian",
		},
		Health: HealthConfig{
			DiskPath: DefaultDiskPath,
		},
	}
}

// Load reads a YAML config file and merges it with defaults.
func Load(path string) (*Config, error) {
	cfg := DefaultConfig()

	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, err
	}

	return cfg, nil
}
