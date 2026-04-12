//go:build android

package service

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
)

// ErrAndroidPersistenceUnsupported is returned by Enable when boot-time persistence is requested.
var ErrAndroidPersistenceUnsupported = errors.New(
	"enable/disable not supported on android — service will not auto-start at boot")

// AndroidExecutor manages processes on Android using direct process control.
type AndroidExecutor struct{}

func newPlatformExecutor() ServiceExecutor {
	return &AndroidExecutor{}
}

// IsActive checks if a process with the given name is running via pidof.
func (a *AndroidExecutor) IsActive(ctx context.Context, name string) (bool, error) {
	cmd := exec.CommandContext(ctx, "pidof", name)
	output, err := cmd.Output()
	if err != nil {
		return false, nil
	}
	return strings.TrimSpace(string(output)) != "", nil
}

// IsEnabled is a no-op on Android.
func (a *AndroidExecutor) IsEnabled(_ context.Context, _ string) (bool, error) {
	return false, nil
}

// Start launches a process by name.
func (a *AndroidExecutor) Start(ctx context.Context, name string) error {
	cmd := exec.CommandContext(ctx, name)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start process %s: %w", name, err)
	}
	go cmd.Wait()
	return nil
}

// Stop finds processes matching name via pidof and sends SIGTERM.
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

// Enable returns ErrAndroidPersistenceUnsupported.
func (a *AndroidExecutor) Enable(_ context.Context, _ string) error {
	return ErrAndroidPersistenceUnsupported
}

// Disable is a no-op.
func (a *AndroidExecutor) Disable(_ context.Context, _ string) error {
	return nil
}
