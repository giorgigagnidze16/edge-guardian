//go:build windows

package health

import (
	"os"
	"syscall"
	"unsafe"

	"github.com/edgeguardian/agent/internal/model"
)

var (
	kernel32          = syscall.NewLazyDLL("kernel32.dll")
	getSystemTimes    = kernel32.NewProc("GetSystemTimes")
	globalMemStatusEx = kernel32.NewProc("GlobalMemoryStatusEx")
	getTickCount64    = kernel32.NewProc("GetTickCount64")
	getDiskFreeSpace  = kernel32.NewProc("GetDiskFreeSpaceExW")
)

type filetime struct {
	LowDateTime  uint32
	HighDateTime uint32
}

func filetimeToUint64(ft filetime) uint64 {
	return uint64(ft.HighDateTime)<<32 | uint64(ft.LowDateTime)
}

type memoryStatusEx struct {
	Length               uint32
	MemoryLoad           uint32
	TotalPhys            uint64
	AvailPhys            uint64
	TotalPageFile        uint64
	AvailPageFile        uint64
	TotalVirtual         uint64
	AvailVirtual         uint64
	AvailExtendedVirtual uint64
}

// collectCPU uses GetSystemTimes for delta-based CPU usage calculation.
// kernel time includes idle time, so total = kernel + user, and
// active CPU = (deltaTotal - deltaIdle) / deltaTotal.
func (c *Collector) collectCPU(status *model.DeviceStatus) {
	var idleTime, kernelTime, userTime filetime
	ret, _, _ := getSystemTimes.Call(
		uintptr(unsafe.Pointer(&idleTime)),
		uintptr(unsafe.Pointer(&kernelTime)),
		uintptr(unsafe.Pointer(&userTime)),
	)
	if ret == 0 {
		return
	}

	idle := filetimeToUint64(idleTime)
	kernel := filetimeToUint64(kernelTime)
	user := filetimeToUint64(userTime)
	// kernel includes idle time per Windows API docs
	total := kernel + user

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

// collectMemory uses GlobalMemoryStatusEx for RAM usage.
func (c *Collector) collectMemory(status *model.DeviceStatus) {
	var memStatus memoryStatusEx
	memStatus.Length = uint32(unsafe.Sizeof(memStatus))

	ret, _, _ := globalMemStatusEx.Call(uintptr(unsafe.Pointer(&memStatus)))
	if ret == 0 {
		return
	}

	status.MemoryTotalBytes = int64(memStatus.TotalPhys)
	status.MemoryUsedBytes = int64(memStatus.TotalPhys - memStatus.AvailPhys)
}

// collectDisk uses GetDiskFreeSpaceExW on the configured disk path.
// Falls back to %SystemDrive% if configured path is empty.
func (c *Collector) collectDisk(status *model.DeviceStatus) {
	path := c.diskPath
	if path == "" {
		path = os.Getenv("SystemDrive")
		if path == "" {
			path = "C:"
		}
		path += `\`
	}

	root, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return
	}

	var freeBytesAvailable, totalBytes, totalFreeBytes uint64
	ret, _, _ := getDiskFreeSpace.Call(
		uintptr(unsafe.Pointer(root)),
		uintptr(unsafe.Pointer(&freeBytesAvailable)),
		uintptr(unsafe.Pointer(&totalBytes)),
		uintptr(unsafe.Pointer(&totalFreeBytes)),
	)
	if ret == 0 {
		return
	}

	status.DiskTotalBytes = int64(totalBytes)
	status.DiskUsedBytes = int64(totalBytes - totalFreeBytes)
}

// collectUptime uses GetTickCount64 to get system uptime in milliseconds.
// Note: on 32-bit Windows (GOARCH=386), the uintptr return value truncates
// the 64-bit result, causing uptime to wrap after ~49 days. This is not
// an issue on 64-bit builds (amd64/arm64).
func (c *Collector) collectUptime(status *model.DeviceStatus) {
	r1, _, _ := getTickCount64.Call()
	ms := uint64(r1)
	if ms == 0 {
		return
	}
	status.UptimeSeconds = int64(ms / 1000)
}
