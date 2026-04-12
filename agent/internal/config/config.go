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

	MQTT          MQTTConfig          `yaml:"mqtt"`
	Health        HealthConfig        `yaml:"health"`
	OTA           OTAConfig           `yaml:"ota"`
	Auth          AuthConfig          `yaml:"auth"`
	LogForwarding LogForwardingConfig `yaml:"log_forwarding"`
}

// OTAConfig holds OTA update settings.
type OTAConfig struct {
	Enabled  bool   `yaml:"enabled"`
	SignKey  string `yaml:"sign_key"`  // Ed25519 public key hex for signature verification
	CacheDir string `yaml:"cache_dir"` // Directory for staging downloaded artifacts
}

// AuthConfig holds authentication settings used during first-boot enrollment.
// Post-enrollment, the agent authenticates via mTLS identity cert (see MQTTConfig.IdentityCertPath).
type AuthConfig struct {
	EnrollmentToken string `yaml:"enrollment_token"` // one-time token for initial enrollment; ignored once identity cert is persisted
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
// BrokerURL is the enrollment broker (password auth); MutualTLSBrokerURL is the
// production broker used after enrollment with the persisted identity cert.
type MQTTConfig struct {
	BrokerURL          string `yaml:"broker_url"`
	Username           string `yaml:"username"`
	Password           string `yaml:"password"`
	TopicRoot          string `yaml:"topic_root"`
	MutualTLSBrokerURL string `yaml:"mtls_broker_url"`
	CACertPath         string `yaml:"ca_cert_path"`
	IdentityCertPath   string `yaml:"identity_cert_path"`
	IdentityKeyPath    string `yaml:"identity_key_path"`
	TLSServerName      string `yaml:"tls_server_name"`
	InsecureSkipVerify bool   `yaml:"insecure_skip_verify"`
}

// HealthConfig holds health monitoring settings.
type HealthConfig struct {
	DiskPath string `yaml:"disk_path"`
	Port     int    `yaml:"port"`
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
