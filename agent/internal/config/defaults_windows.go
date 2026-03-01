//go:build windows

package config

const (
	// DefaultConfigPath is the standard config location on Windows.
	DefaultConfigPath = `C:\ProgramData\EdgeGuardian\agent.yaml`
	// DefaultDataDir is the standard data directory on Windows.
	DefaultDataDir = `C:\ProgramData\EdgeGuardian\data`
	// DefaultDiskPath is the drive to monitor for disk usage.
	// Override in config if the system drive is not C:.
	DefaultDiskPath = `C:\`
)
