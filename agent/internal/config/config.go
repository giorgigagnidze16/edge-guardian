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
}

// MQTTConfig holds MQTT broker connection settings.
type MQTTConfig struct {
	BrokerURL string `yaml:"broker_url"`
	Username  string `yaml:"username"`
	Password  string `yaml:"password"`
	TopicRoot string `yaml:"topic_root"`
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
		DataDir:           "/var/lib/edgeguardian",
		MQTT: MQTTConfig{
			BrokerURL: "tcp://localhost:1883",
			TopicRoot: "edgeguardian",
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
