// Package health collects system metrics from the host.
// Platform-specific implementations are in collector_linux.go,
// collector_windows.go, and collector_darwin.go.
package health

import (
	"fmt"

	"github.com/edgeguardian/agent/internal/model"
)

// Collector gathers system health metrics.
type Collector struct {
	diskPath  string
	prevIdle  uint64
	prevTotal uint64
}

// New creates a health Collector.
// diskPath is the filesystem path or drive to monitor for disk usage
// (e.g. "/" on Linux/macOS, "C:\" on Windows).
func New(diskPath string) *Collector {
	return &Collector{diskPath: diskPath}
}

// Collect gathers current system metrics and returns a DeviceStatus.
func (c *Collector) Collect() *model.DeviceStatus {
	status := &model.DeviceStatus{
		State: "online",
	}

	c.collectCPU(status)
	c.collectMemory(status)
	c.collectDisk(status)
	c.collectTemperature(status)
	c.collectUptime(status)
	c.computeState(status)

	return status
}

// computeState checks resource thresholds and sets degraded state.
func (c *Collector) computeState(status *model.DeviceStatus) {
	if status.TemperatureCelsius > 80 {
		status.State = "degraded"
	}
	if status.MemoryTotalBytes > 0 {
		memPct := float64(status.MemoryUsedBytes) / float64(status.MemoryTotalBytes) * 100
		if memPct > 95 {
			status.State = "degraded"
		}
	}
	if status.DiskTotalBytes > 0 {
		diskPct := float64(status.DiskUsedBytes) / float64(status.DiskTotalBytes) * 100
		if diskPct > 95 {
			status.State = "degraded"
		}
	}
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
