package health

import (
	"strings"
	"testing"

	"github.com/edgeguardian/agent/internal/model"
)

func TestNew_SetsDiskPath(t *testing.T) {
	c := New("/mnt/data")
	if c.diskPath != "/mnt/data" {
		t.Fatalf("expected diskPath=/mnt/data, got %q", c.diskPath)
	}
}

func TestNew_EmptyDiskPath(t *testing.T) {
	c := New("")
	if c.diskPath != "" {
		t.Fatalf("expected empty diskPath, got %q", c.diskPath)
	}
}

func TestComputeState_Healthy(t *testing.T) {
	c := New("/")
	status := &model.DeviceStatus{
		State:              "online",
		CPUUsagePercent:    30,
		MemoryUsedBytes:    500_000_000,
		MemoryTotalBytes:   4_000_000_000,
		DiskUsedBytes:      20_000_000_000,
		DiskTotalBytes:     100_000_000_000,
		TemperatureCelsius: 45.0,
	}
	c.computeState(status)
	if status.State != "online" {
		t.Fatalf("expected state=online, got %q", status.State)
	}
}

func TestComputeState_HighMemory(t *testing.T) {
	c := New("/")
	status := &model.DeviceStatus{
		State:            "online",
		MemoryUsedBytes:  3_900_000_000,
		MemoryTotalBytes: 4_000_000_000, // 97.5% used
	}
	c.computeState(status)
	if status.State != "degraded" {
		t.Fatalf("expected state=degraded for high memory, got %q", status.State)
	}
}

func TestComputeState_HighDisk(t *testing.T) {
	c := New("/")
	status := &model.DeviceStatus{
		State:          "online",
		DiskUsedBytes:  98_000_000_000,
		DiskTotalBytes: 100_000_000_000, // 98% used
	}
	c.computeState(status)
	if status.State != "degraded" {
		t.Fatalf("expected state=degraded for high disk, got %q", status.State)
	}
}

func TestComputeState_HighTemperature(t *testing.T) {
	c := New("/")
	status := &model.DeviceStatus{
		State:              "online",
		TemperatureCelsius: 85.0,
	}
	c.computeState(status)
	if status.State != "degraded" {
		t.Fatalf("expected state=degraded for high temp, got %q", status.State)
	}
}

func TestComputeState_BelowThresholds(t *testing.T) {
	c := New("/")
	status := &model.DeviceStatus{
		State:              "online",
		MemoryUsedBytes:    2_000_000_000,
		MemoryTotalBytes:   4_000_000_000, // 50%
		DiskUsedBytes:      50_000_000_000,
		DiskTotalBytes:     100_000_000_000, // 50%
		TemperatureCelsius: 79.9,
	}
	c.computeState(status)
	if status.State != "online" {
		t.Fatalf("expected state=online for below thresholds, got %q", status.State)
	}
}

func TestFormatMetrics_ProducesValidString(t *testing.T) {
	status := &model.DeviceStatus{
		State:              "online",
		CPUUsagePercent:    42.5,
		MemoryUsedBytes:    2 * 1024 * 1024 * 1024,
		MemoryTotalBytes:   4 * 1024 * 1024 * 1024,
		DiskUsedBytes:      50 * 1024 * 1024 * 1024,
		DiskTotalBytes:     100 * 1024 * 1024 * 1024,
		TemperatureCelsius: 55.3,
		UptimeSeconds:      3600,
	}

	out := FormatMetrics(status)

	for _, want := range []string{"cpu=", "mem=", "disk=", "temp=", "uptime=", "state=online"} {
		if !strings.Contains(out, want) {
			t.Errorf("FormatMetrics output missing %q: %s", want, out)
		}
	}
}

func TestCollect_PopulatesStatus(t *testing.T) {
	c := New("")
	status := c.Collect()

	if status.State == "" {
		t.Fatal("expected non-empty State")
	}
	if status.State != "online" && status.State != "degraded" {
		t.Fatalf("expected state online or degraded, got %q", status.State)
	}
	// Memory should be populated on any OS.
	if status.MemoryTotalBytes <= 0 {
		t.Fatal("expected MemoryTotalBytes > 0")
	}
	if status.MemoryUsedBytes < 0 {
		t.Fatal("expected MemoryUsedBytes >= 0")
	}
	// Disk should be populated on any OS.
	if status.DiskTotalBytes <= 0 {
		t.Fatal("expected DiskTotalBytes > 0")
	}
}

func TestCollect_CPUDelta_SecondCallPopulates(t *testing.T) {
	c := New("")

	// First call establishes baseline.
	c.Collect()
	// Second call should compute delta.
	status := c.Collect()

	if status.CPUUsagePercent < 0 || status.CPUUsagePercent > 100 {
		t.Fatalf("CPU usage should be 0-100%%, got %.1f", status.CPUUsagePercent)
	}
}
