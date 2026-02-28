// Package health collects system metrics from the host.
// It reads from /proc and /sys on Linux to gather CPU, memory, disk, and
// temperature data without any external dependencies.
package health

import (
	"github.com/edgeguardian/agent/internal/model"
)

// Collector gathers system health metrics.
type Collector struct {
	prevIdle  uint64
	prevTotal uint64
}

// New creates a health Collector.
func New() *Collector {
	return &Collector{}
}

// Collect gathers current system metrics and returns a DeviceStatus.
// On non-Linux platforms, fields may be zero-valued.
func (c *Collector) Collect() *model.DeviceStatus {
	status := &model.DeviceStatus{
		State: "online",
	}

	c.collectCPU(status)
	c.collectMemory(status)
	c.collectDisk(status)
	c.collectTemperature(status)
	c.collectUptime(status)

	return status
}
