//go:build integration && linux

package service

import (
	"context"
	"os/exec"
	"testing"

	"github.com/edgeguardian/agent/internal/reconciler"
	"go.uber.org/zap"
)

// These tests run inside the Docker integration test container where
// systemd is PID 1 (see Dockerfile.test). They exercise the real
// systemctlExecutor (RealSystemd) against a test service unit.
//
// The test service "eg-test.service" is pre-installed by the Dockerfile.
// If systemd is not running, these tests skip gracefully.

func integrationLogger() *zap.Logger {
	logger, _ := zap.NewDevelopment()
	return logger
}

func requireSystemd(t *testing.T) {
	t.Helper()
	cmd := exec.CommandContext(context.Background(), "systemctl", "is-system-running")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Skipf("systemd not available (skipping): %s %v", string(out), err)
	}
}

func requireTestUnit(t *testing.T) {
	t.Helper()
	cmd := exec.CommandContext(context.Background(), "systemctl", "cat", "eg-test.service")
	if err := cmd.Run(); err != nil {
		t.Skipf("eg-test.service unit not installed (skipping): %v", err)
	}
}

func resetService(t *testing.T) {
	t.Helper()
	exec.CommandContext(context.Background(), "systemctl", "stop", "eg-test").Run()
	exec.CommandContext(context.Background(), "systemctl", "disable", "eg-test").Run()
}

func TestIntegration_StartService(t *testing.T) {
	requireSystemd(t)
	requireTestUnit(t)
	resetService(t)

	sm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "eg-test",
			Fields: map[string]interface{}{
				"name":  "eg-test",
				"state": "running",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("expected ActionUpdate, got %s", actions[0].ActionType)
	}

	// Verify it's actually running.
	active, err := sm.executor.IsActive(context.Background(), "eg-test")
	if err != nil {
		t.Fatalf("IsActive: %v", err)
	}
	if !active {
		t.Fatal("expected service to be running")
	}
}

func TestIntegration_StopService(t *testing.T) {
	requireSystemd(t)
	requireTestUnit(t)

	// Start it first.
	exec.CommandContext(context.Background(), "systemctl", "start", "eg-test").Run()

	sm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "eg-test",
			Fields: map[string]interface{}{
				"name":  "eg-test",
				"state": "stopped",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	active, _ := sm.executor.IsActive(context.Background(), "eg-test")
	if active {
		t.Fatal("expected service to be stopped")
	}
}

func TestIntegration_EnableService(t *testing.T) {
	requireSystemd(t)
	requireTestUnit(t)
	resetService(t)

	sm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "eg-test",
			Fields: map[string]interface{}{
				"name":    "eg-test",
				"state":   "running",
				"enabled": "true",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	enabled, _ := sm.executor.IsEnabled(context.Background(), "eg-test")
	if !enabled {
		t.Fatal("expected service to be enabled")
	}
}

func TestIntegration_NoopWhenConverged(t *testing.T) {
	requireSystemd(t)
	requireTestUnit(t)

	exec.CommandContext(context.Background(), "systemctl", "start", "eg-test").Run()
	exec.CommandContext(context.Background(), "systemctl", "enable", "eg-test").Run()

	sm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "eg-test",
			Fields: map[string]interface{}{
				"name":    "eg-test",
				"state":   "running",
				"enabled": "true",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionNoop {
		t.Fatalf("expected ActionNoop, got %s (desc: %s, err: %s)",
			actions[0].ActionType, actions[0].Description, actions[0].Error)
	}
}

func TestIntegration_DriftDetection(t *testing.T) {
	requireSystemd(t)
	requireTestUnit(t)
	resetService(t)

	sm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "eg-test",
			Fields: map[string]interface{}{
				"name":  "eg-test",
				"state": "running",
			},
		},
	}

	// First reconcile: start.
	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("first: expected ActionUpdate, got %s", actions[0].ActionType)
	}

	// Second: noop.
	actions = sm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionNoop {
		t.Fatalf("second: expected ActionNoop, got %s", actions[0].ActionType)
	}

	// Simulate drift: stop externally.
	exec.CommandContext(context.Background(), "systemctl", "stop", "eg-test").Run()

	// Third: should detect drift and restart.
	actions = sm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("third (drift): expected ActionUpdate, got %s", actions[0].ActionType)
	}

	active, _ := sm.executor.IsActive(context.Background(), "eg-test")
	if !active {
		t.Fatal("expected service running after drift correction")
	}
}

func TestIntegration_DisableAndStop(t *testing.T) {
	requireSystemd(t)
	requireTestUnit(t)

	exec.CommandContext(context.Background(), "systemctl", "start", "eg-test").Run()
	exec.CommandContext(context.Background(), "systemctl", "enable", "eg-test").Run()

	sm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "eg-test",
			Fields: map[string]interface{}{
				"name":    "eg-test",
				"state":   "stopped",
				"enabled": "false",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	active, _ := sm.executor.IsActive(context.Background(), "eg-test")
	if active {
		t.Fatal("expected stopped")
	}

	enabled, _ := sm.executor.IsEnabled(context.Background(), "eg-test")
	if enabled {
		t.Fatal("expected disabled")
	}
}
