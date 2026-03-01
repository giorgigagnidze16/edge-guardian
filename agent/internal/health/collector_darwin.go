//go:build darwin

package health

import (
	"context"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/edgeguardian/agent/internal/model"
	"golang.org/x/sys/unix"
)

// collectCPU uses `ps` to sum per-process CPU usage, normalized by logical CPU count.
// macOS does not expose kernel CPU counters without cgo (host_processor_info
// requires Mach IPC). The ps approach is the standard non-cgo fallback used
// by many Go monitoring tools.
func (c *Collector) collectCPU(status *model.DeviceStatus) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	out, err := exec.CommandContext(ctx, "ps", "-A", "-o", "%cpu").Output()
	if err != nil {
		return
	}

	var total float64
	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	for _, line := range lines[1:] { // skip header
		val, err := strconv.ParseFloat(strings.TrimSpace(line), 64)
		if err != nil {
			continue
		}
		total += val
	}

	// ps reports per-core percentages (100% = one full core).
	// Normalize to whole-system percentage.
	numCPU := runtime.NumCPU()
	if numCPU > 0 {
		status.CPUUsagePercent = total / float64(numCPU)
	}
}

// collectMemory uses sysctl for total memory and parses vm_stat output for
// accurate available-memory calculation. Unlike raw vm.page_free_count (which
// only counts truly free pages), this includes inactive, purgeable, and
// speculative pages that macOS considers reclaimable.
func (c *Collector) collectMemory(status *model.DeviceStatus) {
	memsize, err := unix.SysctlUint64("hw.memsize")
	if err != nil {
		return
	}
	status.MemoryTotalBytes = int64(memsize)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	out, err := exec.CommandContext(ctx, "vm_stat").Output()
	if err != nil {
		return
	}

	lines := strings.Split(string(out), "\n")

	// First line: "Mach Virtual Memory Statistics: (page size of 16384 bytes)"
	var pageSize int64 = 4096
	if len(lines) > 0 {
		if idx := strings.Index(lines[0], "page size of "); idx != -1 {
			sizeStr := lines[0][idx+len("page size of "):]
			if endIdx := strings.Index(sizeStr, " "); endIdx != -1 {
				if ps, err := strconv.ParseInt(sizeStr[:endIdx], 10, 64); err == nil {
					pageSize = ps
				}
			}
		}
	}

	// Parse page counts from remaining lines.
	// Format: "Pages free:                    12345."
	pages := make(map[string]int64)
	for _, line := range lines[1:] {
		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.TrimSpace(parts[0])
		valStr := strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(parts[1]), "."))
		val, err := strconv.ParseInt(valStr, 10, 64)
		if err != nil {
			continue
		}
		pages[key] = val
	}

	// Available memory = free + inactive + purgeable + speculative.
	// These are all pages macOS can reclaim without swapping.
	available := (pages["Pages free"] + pages["Pages inactive"] +
		pages["Pages purgeable"] + pages["Pages speculative"]) * pageSize

	if available > int64(memsize) {
		available = int64(memsize)
	}
	status.MemoryUsedBytes = int64(memsize) - available
}

// collectDisk uses unix.Statfs on the configured disk path.
func (c *Collector) collectDisk(status *model.DeviceStatus) {
	path := c.diskPath
	if path == "" {
		path = "/"
	}

	var stat unix.Statfs_t
	if err := unix.Statfs(path, &stat); err != nil {
		return
	}

	total := uint64(stat.Blocks) * uint64(stat.Bsize)
	free := uint64(stat.Bfree) * uint64(stat.Bsize)
	status.DiskTotalBytes = int64(total)
	status.DiskUsedBytes = int64(total - free)
}

// collectTemperature is a no-op on macOS.
// Reading SMC temperature sensors requires IOKit and cgo.
func (c *Collector) collectTemperature(status *model.DeviceStatus) {}

// collectUptime uses sysctl kern.boottime to calculate uptime.
func (c *Collector) collectUptime(status *model.DeviceStatus) {
	tv, err := unix.SysctlTimeval("kern.boottime")
	if err != nil {
		return
	}
	bootTime := time.Unix(tv.Sec, int64(tv.Usec)*1000)
	status.UptimeSeconds = int64(time.Since(bootTime).Seconds())
}
