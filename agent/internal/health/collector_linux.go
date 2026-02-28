//go:build linux

package health

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"
	"syscall"

	"github.com/edgeguardian/agent/internal/model"
)

// collectCPU reads /proc/stat to calculate CPU usage as a percentage.
// Uses delta between two reads (stored in Collector state).
func (c *Collector) collectCPU(status *model.DeviceStatus) {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	if !scanner.Scan() {
		return
	}

	// First line: cpu  user nice system idle iowait irq softirq steal guest guest_nice
	fields := strings.Fields(scanner.Text())
	if len(fields) < 5 || fields[0] != "cpu" {
		return
	}

	var total, idle uint64
	for i := 1; i < len(fields); i++ {
		val, err := strconv.ParseUint(fields[i], 10, 64)
		if err != nil {
			continue
		}
		total += val
		if i == 4 { // idle is field index 4 (user=1, nice=2, system=3, idle=4)
			idle = val
		}
	}

	if c.prevTotal > 0 {
		deltaTotal := total - c.prevTotal
		deltaIdle := idle - c.prevIdle
		if deltaTotal > 0 {
			status.CPUUsagePercent = float64(deltaTotal-deltaIdle) / float64(deltaTotal) * 100.0
		}
	}

	c.prevTotal = total
	c.prevIdle = idle
}

// collectMemory reads /proc/meminfo for RAM usage.
func (c *Collector) collectMemory(status *model.DeviceStatus) {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return
	}
	defer f.Close()

	var memTotal, memAvailable int64
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		parts := strings.Fields(line)
		if len(parts) < 2 {
			continue
		}
		val, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil {
			continue
		}
		// Values are in kB.
		switch parts[0] {
		case "MemTotal:":
			memTotal = val * 1024
		case "MemAvailable:":
			memAvailable = val * 1024
		}
	}

	status.MemoryTotalBytes = memTotal
	if memTotal > 0 {
		status.MemoryUsedBytes = memTotal - memAvailable
	}
}

// collectDisk uses statfs to get root filesystem usage.
func (c *Collector) collectDisk(status *model.DeviceStatus) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs("/", &stat); err != nil {
		return
	}

	total := stat.Blocks * uint64(stat.Bsize)
	free := stat.Bfree * uint64(stat.Bsize)
	status.DiskTotalBytes = int64(total)
	status.DiskUsedBytes = int64(total - free)
}

// collectTemperature reads the CPU thermal zone from sysfs.
func (c *Collector) collectTemperature(status *model.DeviceStatus) {
	// Try common thermal zone paths.
	paths := []string{
		"/sys/class/thermal/thermal_zone0/temp",
		"/sys/class/hwmon/hwmon0/temp1_input",
	}

	for _, path := range paths {
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		raw := strings.TrimSpace(string(data))
		milliC, err := strconv.ParseFloat(raw, 64)
		if err != nil {
			continue
		}
		// Value is in millidegrees Celsius.
		status.TemperatureCelsius = milliC / 1000.0
		return
	}
}

// collectUptime reads /proc/uptime.
func (c *Collector) collectUptime(status *model.DeviceStatus) {
	data, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return
	}
	fields := strings.Fields(string(data))
	if len(fields) < 1 {
		return
	}
	uptimeFloat, err := strconv.ParseFloat(fields[0], 64)
	if err != nil {
		return
	}
	status.UptimeSeconds = int64(uptimeFloat)

	// Set state based on temperature thresholds.
	if status.TemperatureCelsius > 80 {
		status.State = "degraded"
	}
	// Check memory pressure (>95% used).
	if status.MemoryTotalBytes > 0 {
		memPct := float64(status.MemoryUsedBytes) / float64(status.MemoryTotalBytes) * 100
		if memPct > 95 {
			status.State = "degraded"
		}
	}
	// Check disk pressure (>95% used).
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
