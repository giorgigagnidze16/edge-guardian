//go:build darwin

package config

const (
	// DefaultConfigPath is the standard config location on macOS.
	DefaultConfigPath = "/etc/edgeguardian/agent.yaml"
	// DefaultDataDir is the standard data directory on macOS.
	DefaultDataDir = "/var/lib/edgeguardian"
	// DefaultDiskPath is the filesystem root to monitor for disk usage.
	DefaultDiskPath = "/"
)
