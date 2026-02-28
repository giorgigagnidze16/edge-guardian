//go:build !linux

package health

import (
	"fmt"
	"runtime"

	"github.com/edgeguardian/agent/internal/model"
)

// collectCPU is a stub on non-Linux platforms.
func (c *Collector) collectCPU(status *model.DeviceStatus) {
	// CPU metrics from /proc/stat are Linux-only.
}

// collectMemory uses Go runtime stats as a rough proxy on non-Linux.
func (c *Collector) collectMemory(status *model.DeviceStatus) {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	status.MemoryUsedBytes = int64(m.Sys)
}

// collectDisk is a stub on non-Linux platforms.
func (c *Collector) collectDisk(status *model.DeviceStatus) {
	// Disk metrics via statfs are Linux-only.
}

// collectTemperature is a stub on non-Linux platforms.
func (c *Collector) collectTemperature(status *model.DeviceStatus) {
	// Temperature sensors are Linux-only.
}

// collectUptime is a stub on non-Linux platforms.
func (c *Collector) collectUptime(status *model.DeviceStatus) {
	// Uptime from /proc is Linux-only.
}

// FormatMetrics returns a human-readable summary of metrics (for logging).
func FormatMetrics(s *model.DeviceStatus) string {
	return fmt.Sprintf("cpu=%.1f%% mem=%d/%dMB disk=%d/%dGB temp=%.1fC uptime=%ds state=%s",
		s.CPUUsagePercent,
		s.MemoryUsedBytes/(1024*1024), s.MemoryTotalBytes/(1024*1024),
		s.DiskUsedBytes/(1024*1024*1024), s.DiskTotalBytes/(1024*1024*1024),
		s.TemperatureCelsius,
		s.UptimeSeconds,
		s.State,
	)
}
