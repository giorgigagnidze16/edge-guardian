//go:build integration && linux

package health

import (
	"os"
	"strings"
	"testing"
)

// These tests run inside a Linux container and verify that the health
// collector reads from real kernel interfaces (/proc, /sys).

func TestLinux_ProcStatExists(t *testing.T) {
	data, err := os.ReadFile("/proc/stat")
	if err != nil {
		t.Fatalf("read /proc/stat: %v", err)
	}
	if !strings.HasPrefix(string(data), "cpu") {
		t.Fatalf("expected /proc/stat to start with 'cpu', got: %.50s", string(data))
	}
}

func TestLinux_ProcMeminfoExists(t *testing.T) {
	data, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		t.Fatalf("read /proc/meminfo: %v", err)
	}
	if !strings.Contains(string(data), "MemTotal:") {
		t.Fatal("expected /proc/meminfo to contain MemTotal:")
	}
	if !strings.Contains(string(data), "MemAvailable:") {
		t.Fatal("expected /proc/meminfo to contain MemAvailable:")
	}
}

func TestLinux_ProcUptimeExists(t *testing.T) {
	data, err := os.ReadFile("/proc/uptime")
	if err != nil {
		t.Fatalf("read /proc/uptime: %v", err)
	}
	fields := strings.Fields(string(data))
	if len(fields) < 1 {
		t.Fatal("expected at least 1 field in /proc/uptime")
	}
}

func TestLinux_CollectCPU_ReadsProc(t *testing.T) {
	c := New("/")

	// First call sets baseline.
	status1 := c.Collect()
	if status1.CPUUsagePercent < 0 {
		t.Fatalf("first collect: CPU should be >= 0, got %f", status1.CPUUsagePercent)
	}

	// Second call computes delta.
	status2 := c.Collect()
	if status2.CPUUsagePercent < 0 || status2.CPUUsagePercent > 100 {
		t.Fatalf("second collect: CPU should be 0-100%%, got %f", status2.CPUUsagePercent)
	}
}

func TestLinux_CollectMemory_ReadsProc(t *testing.T) {
	c := New("/")
	status := c.Collect()

	if status.MemoryTotalBytes <= 0 {
		t.Fatalf("expected MemoryTotalBytes > 0, got %d", status.MemoryTotalBytes)
	}
	if status.MemoryUsedBytes < 0 {
		t.Fatalf("expected MemoryUsedBytes >= 0, got %d", status.MemoryUsedBytes)
	}
	if status.MemoryUsedBytes > status.MemoryTotalBytes {
		t.Fatalf("MemoryUsedBytes (%d) > MemoryTotalBytes (%d)",
			status.MemoryUsedBytes, status.MemoryTotalBytes)
	}
}

func TestLinux_CollectDisk_ReadsStatfs(t *testing.T) {
	c := New("/")
	status := c.Collect()

	if status.DiskTotalBytes <= 0 {
		t.Fatalf("expected DiskTotalBytes > 0, got %d", status.DiskTotalBytes)
	}
	if status.DiskUsedBytes < 0 {
		t.Fatalf("expected DiskUsedBytes >= 0, got %d", status.DiskUsedBytes)
	}
	if status.DiskUsedBytes > status.DiskTotalBytes {
		t.Fatalf("DiskUsedBytes (%d) > DiskTotalBytes (%d)",
			status.DiskUsedBytes, status.DiskTotalBytes)
	}
}

func TestLinux_CollectUptime_ReadsProc(t *testing.T) {
	c := New("/")
	status := c.Collect()

	if status.UptimeSeconds <= 0 {
		t.Fatalf("expected UptimeSeconds > 0, got %d", status.UptimeSeconds)
	}
}

func TestLinux_CollectDisk_CustomPath(t *testing.T) {
	// /tmp should be a valid mountpoint in any Linux container.
	c := New("/tmp")
	status := c.Collect()

	if status.DiskTotalBytes <= 0 {
		t.Fatalf("expected DiskTotalBytes > 0 for /tmp, got %d", status.DiskTotalBytes)
	}
}

func TestLinux_ComputeState_EndToEnd(t *testing.T) {
	c := New("/")
	status := c.Collect()

	// On a normal container, state should be "online" (not hitting thresholds).
	if status.State != "online" && status.State != "degraded" {
		t.Fatalf("expected state online or degraded, got %q", status.State)
	}
}
