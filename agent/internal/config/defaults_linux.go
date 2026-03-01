//go:build linux

package config

const (
	// DefaultConfigPath is the standard config location on Linux.
	DefaultConfigPath = "/etc/edgeguardian/agent.yaml"
	// DefaultDataDir is the standard data directory on Linux.
	DefaultDataDir = "/var/lib/edgeguardian"
	// DefaultDiskPath is the filesystem root to monitor for disk usage.
	DefaultDiskPath = "/"
)
