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

	// OTA update configuration
	OTA OTAConfig `yaml:"ota"`

	// Authentication configuration
	Auth AuthConfig `yaml:"auth"`

	// Log forwarding configuration
	LogForwarding LogForwardingConfig `yaml:"log_forwarding"`
}

// OTAConfig holds OTA update settings.
type OTAConfig struct {
	Enabled  bool   `yaml:"enabled"`
	SignKey  string `yaml:"sign_key"`  // Ed25519 public key hex for signature verification
	CacheDir string `yaml:"cache_dir"` // Directory for staging downloaded artifacts
}

// AuthConfig holds authentication settings for enrolled devices.
type AuthConfig struct {
	EnrollmentToken string `yaml:"enrollment_token"` // Token for initial enrollment
	DeviceToken     string `yaml:"device_token"`     // JWT for authenticated API calls
	TokenFile       string `yaml:"token_file"`       // Path to persist device token
}

// LogForwardingConfig holds log forwarding settings.
type LogForwardingConfig struct {
	Enabled       bool   `yaml:"enabled"`
	Source        string `yaml:"source"`         // "journald" or "file"
	FilePath      string `yaml:"file_path"`      // Path to log file (when source=file)
	BatchSize     int    `yaml:"batch_size"`     // Number of lines per MQTT batch
	FlushInterval int    `yaml:"flush_interval"` // Seconds between flushes
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

	// Port for the local health HTTP server (used by watchdog).
	Port int `yaml:"port"`
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
			Port:     8484,
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
