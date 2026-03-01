//go:build linux && !android

package service

import (
	"context"
	"fmt"
	"os/exec"
	"strings"
)

// RealSystemd executes actual systemctl commands on Linux.
type RealSystemd struct{}

func newPlatformExecutor() ServiceExecutor {
	return &RealSystemd{}
}

func (r *RealSystemd) IsActive(ctx context.Context, name string) (bool, error) {
	return r.runCheck(ctx, "is-active", name)
}

func (r *RealSystemd) IsEnabled(ctx context.Context, name string) (bool, error) {
	return r.runCheck(ctx, "is-enabled", name)
}

func (r *RealSystemd) Start(ctx context.Context, name string) error {
	return r.run(ctx, "start", name)
}

func (r *RealSystemd) Stop(ctx context.Context, name string) error {
	return r.run(ctx, "stop", name)
}

func (r *RealSystemd) Enable(ctx context.Context, name string) error {
	return r.run(ctx, "enable", name)
}

func (r *RealSystemd) Disable(ctx context.Context, name string) error {
	return r.run(ctx, "disable", name)
}

func (r *RealSystemd) runCheck(ctx context.Context, verb, name string) (bool, error) {
	cmd := exec.CommandContext(ctx, "systemctl", verb, name)
	output, err := cmd.CombinedOutput()
	result := strings.TrimSpace(string(output))

	if err != nil {
		// systemctl exits non-zero for "inactive"/"disabled" — that is not an error.
		if result == "inactive" || result == "disabled" || result == "unknown" {
			return false, nil
		}
		return false, fmt.Errorf("systemctl %s %s: %s: %w", verb, name, result, err)
	}
	return result == "active" || result == "enabled", nil
}

func (r *RealSystemd) run(ctx context.Context, verb, name string) error {
	cmd := exec.CommandContext(ctx, "systemctl", verb, name)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl %s %s: %s: %w", verb, name, strings.TrimSpace(string(output)), err)
	}
	return nil
}
