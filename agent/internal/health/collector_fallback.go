//go:build !linux && !windows && !darwin && !android

package health

import (
	"runtime"

	"github.com/edgeguardian/agent/internal/model"
)

// Fallback implementations for platforms without dedicated support.
// CPU, disk, temperature, and uptime return zero values.
// Memory uses Go runtime stats as a rough proxy.

func (c *Collector) collectCPU(status *model.DeviceStatus) {}

func (c *Collector) collectMemory(status *model.DeviceStatus) {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	status.MemoryUsedBytes = int64(m.Sys)
}

func (c *Collector) collectDisk(status *model.DeviceStatus) {}

func (c *Collector) collectTemperature(status *model.DeviceStatus) {}

func (c *Collector) collectUptime(status *model.DeviceStatus) {}
