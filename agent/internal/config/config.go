package config

import (
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

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

type OTAConfig struct {
	Enabled    bool   `yaml:"enabled"`
	AutoUpdate bool   `yaml:"auto_update"`
	SignKey    string `yaml:"sign_key"`
	CacheDir   string `yaml:"cache_dir"`
	Insecure   bool   `yaml:"insecure"`
}

type AuthConfig struct {
	EnrollmentToken string `yaml:"enrollment_token"`
}

type LogForwardingConfig struct {
	Enabled       bool   `yaml:"enabled"`
	Source        string `yaml:"source"`
	FilePath      string `yaml:"file_path"`
	BatchSize     int    `yaml:"batch_size"`
	FlushInterval int    `yaml:"flush_interval"`
}

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

type HealthConfig struct {
	DiskPath string `yaml:"disk_path"`
	Port     int    `yaml:"port"`
}

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

func (c *Config) ControllerBaseURL() string {
	return fmt.Sprintf("https://%s:%d", c.ControllerAddress, c.ControllerPort)
}

func (c *Config) Validate() error {
	if c.OTA.AutoUpdate && c.OTA.SignKey == "" {
		return fmt.Errorf("ota.auto_update requires ota.sign_key (refusing unsigned auto-update)")
	}
	return nil
}

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
