//go:build android

package config

const (
	// DefaultConfigPath is the config location on Android.
	// /data/local/tmp/ is accessible on rooted devices and via Termux.
	DefaultConfigPath = "/data/local/tmp/edgeguardian/agent.yaml"
	// DefaultDataDir is the data directory on Android.
	DefaultDataDir = "/data/local/tmp/edgeguardian"
	// DefaultDiskPath is the primary data partition to monitor for disk usage.
	DefaultDiskPath = "/data"
)
