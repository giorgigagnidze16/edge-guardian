//go:build darwin

package service

import (
	"context"
	"fmt"
	"os/exec"
	"os/user"
	"strings"
)

// RealLaunchctl manages macOS services via the launchctl command.
// Automatically detects whether to use the "system" domain (when running
// as root) or the "gui/<uid>" domain (when running as a regular user).
type RealLaunchctl struct {
	domain string // "system" or "gui/<uid>"
}

func newPlatformExecutor() ServiceExecutor {
	domain := "system"
	if u, err := user.Current(); err == nil && u.Uid != "0" {
		domain = "gui/" + u.Uid
	}
	return &RealLaunchctl{domain: domain}
}

// target returns the fully-qualified launchd target for a service label.
func (l *RealLaunchctl) target(name string) string {
	return l.domain + "/" + name
}

func (l *RealLaunchctl) IsActive(ctx context.Context, name string) (bool, error) {
	// launchctl list <label> exits 0 if the service is loaded.
	cmd := exec.CommandContext(ctx, "launchctl", "list", name)
	output, err := cmd.CombinedOutput()
	if err != nil {
		// Service not loaded or not found.
		return false, nil
	}
	// launchctl list <name> output has lines with: PID, Status, Label.
	// A running service has a numeric PID; a loaded-but-stopped shows "-".
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	for _, line := range lines {
		fields := strings.Fields(line)
		if len(fields) >= 3 && fields[2] == name {
			return fields[0] != "-" && fields[0] != "0", nil
		}
	}
	// Loaded means it's at least managed by launchd.
	return true, nil
}

func (l *RealLaunchctl) IsEnabled(ctx context.Context, name string) (bool, error) {
	// A loaded service is considered enabled.
	cmd := exec.CommandContext(ctx, "launchctl", "list", name)
	err := cmd.Run()
	return err == nil, nil
}

func (l *RealLaunchctl) Start(ctx context.Context, name string) error {
	// kickstart forces the service to start (modern launchctl).
	cmd := exec.CommandContext(ctx, "launchctl", "kickstart", "-k", l.target(name))
	if _, err := cmd.CombinedOutput(); err != nil {
		// Fallback: legacy start command for older macOS versions.
		cmd2 := exec.CommandContext(ctx, "launchctl", "start", name)
		output2, err2 := cmd2.CombinedOutput()
		if err2 != nil {
			return fmt.Errorf("launchctl start %s: %s: %w", name, strings.TrimSpace(string(output2)), err2)
		}
	}
	return nil
}

func (l *RealLaunchctl) Stop(ctx context.Context, name string) error {
	cmd := exec.CommandContext(ctx, "launchctl", "kill", "SIGTERM", l.target(name))
	if _, err := cmd.CombinedOutput(); err != nil {
		// Fallback: legacy stop command for older macOS versions.
		cmd2 := exec.CommandContext(ctx, "launchctl", "stop", name)
		output2, err2 := cmd2.CombinedOutput()
		if err2 != nil {
			return fmt.Errorf("launchctl stop %s: %s: %w", name, strings.TrimSpace(string(output2)), err2)
		}
	}
	return nil
}

func (l *RealLaunchctl) Enable(ctx context.Context, name string) error {
	cmd := exec.CommandContext(ctx, "launchctl", "enable", l.target(name))
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("launchctl enable %s: %s: %w", name, strings.TrimSpace(string(output)), err)
	}
	return nil
}

func (l *RealLaunchctl) Disable(ctx context.Context, name string) error {
	cmd := exec.CommandContext(ctx, "launchctl", "disable", l.target(name))
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("launchctl disable %s: %s: %w", name, strings.TrimSpace(string(output)), err)
	}
	return nil
}
