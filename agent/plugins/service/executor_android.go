//go:build android

package service

import (
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
)

// AndroidExecutor manages processes on Android using direct process control.
// Android lacks systemd, so service management uses pidof for status checks
// and direct process execution for start/stop.
type AndroidExecutor struct{}

func newPlatformExecutor() ServiceExecutor {
	return &AndroidExecutor{}
}

// IsActive checks if a process with the given name is running via pidof.
func (a *AndroidExecutor) IsActive(ctx context.Context, name string) (bool, error) {
	cmd := exec.CommandContext(ctx, "pidof", name)
	output, err := cmd.Output()
	if err != nil {
		// pidof exits non-zero when process is not found — not an error.
		return false, nil
	}
	return strings.TrimSpace(string(output)) != "", nil
}

// IsEnabled is a no-op on Android. There is no systemd-like enable/disable concept.
func (a *AndroidExecutor) IsEnabled(_ context.Context, _ string) (bool, error) {
	return false, nil
}

// Start launches a process by name using direct execution.
func (a *AndroidExecutor) Start(ctx context.Context, name string) error {
	cmd := exec.CommandContext(ctx, name)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start process %s: %w", name, err)
	}
	// Detach — don't wait for the process.
	go cmd.Wait()
	return nil
}

// Stop finds a process by name via pidof and sends SIGTERM.
func (a *AndroidExecutor) Stop(ctx context.Context, name string) error {
	cmd := exec.CommandContext(ctx, "pidof", name)
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("process %s not found: %w", name, err)
	}

	pidStr := strings.TrimSpace(out.String())
	if pidStr == "" {
		return fmt.Errorf("process %s not found", name)
	}

	// pidof may return multiple PIDs separated by spaces; signal each.
	for _, p := range strings.Fields(pidStr) {
		pid, err := strconv.Atoi(p)
		if err != nil {
			continue
		}
		if err := syscall.Kill(pid, syscall.SIGTERM); err != nil {
			return fmt.Errorf("kill pid %d (%s): %w", pid, name, err)
		}
	}
	return nil
}

// Enable is a no-op on Android.
func (a *AndroidExecutor) Enable(_ context.Context, _ string) error {
	return nil
}

// Disable is a no-op on Android.
func (a *AndroidExecutor) Disable(_ context.Context, _ string) error {
	return nil
}
