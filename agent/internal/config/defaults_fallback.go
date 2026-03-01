//go:build !linux && !windows && !darwin && !android

package config

const (
	// DefaultConfigPath is a reasonable default for unknown Unix-like platforms.
	DefaultConfigPath = "/etc/edgeguardian/agent.yaml"
	// DefaultDataDir is a reasonable default for unknown platforms.
	DefaultDataDir = "/var/lib/edgeguardian"
	// DefaultDiskPath is the filesystem root to monitor for disk usage.
	DefaultDiskPath = "/"
)
