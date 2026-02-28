package service

import (
	"context"
	"fmt"
	"testing"

	"github.com/edgeguardian/agent/internal/reconciler"
	"go.uber.org/zap"
)

// mockExecutor records calls and returns configured responses.
type mockExecutor struct {
	activeState  map[string]bool
	enabledState map[string]bool
	calls        []string
	failOn       string // verb to fail on, e.g. "start"
}

func newMockExecutor() *mockExecutor {
	return &mockExecutor{
		activeState:  make(map[string]bool),
		enabledState: make(map[string]bool),
	}
}

func (m *mockExecutor) IsActive(_ context.Context, name string) (bool, error) {
	m.calls = append(m.calls, "is-active:"+name)
	return m.activeState[name], nil
}

func (m *mockExecutor) IsEnabled(_ context.Context, name string) (bool, error) {
	m.calls = append(m.calls, "is-enabled:"+name)
	return m.enabledState[name], nil
}

func (m *mockExecutor) Start(_ context.Context, name string) error {
	m.calls = append(m.calls, "start:"+name)
	if m.failOn == "start" {
		return fmt.Errorf("mock start failure")
	}
	m.activeState[name] = true
	return nil
}

func (m *mockExecutor) Stop(_ context.Context, name string) error {
	m.calls = append(m.calls, "stop:"+name)
	if m.failOn == "stop" {
		return fmt.Errorf("mock stop failure")
	}
	m.activeState[name] = false
	return nil
}

func (m *mockExecutor) Enable(_ context.Context, name string) error {
	m.calls = append(m.calls, "enable:"+name)
	if m.failOn == "enable" {
		return fmt.Errorf("mock enable failure")
	}
	m.enabledState[name] = true
	return nil
}

func (m *mockExecutor) Disable(_ context.Context, name string) error {
	m.calls = append(m.calls, "disable:"+name)
	if m.failOn == "disable" {
		return fmt.Errorf("mock disable failure")
	}
	m.enabledState[name] = false
	return nil
}

func testLogger() *zap.Logger {
	logger, _ := zap.NewDevelopment()
	return logger
}

func TestServiceManager_Name(t *testing.T) {
	sm := New(testLogger())
	if sm.Name() != "service_manager" {
		t.Fatalf("expected 'service_manager', got %q", sm.Name())
	}
}

func TestServiceManager_CanHandle(t *testing.T) {
	sm := New(testLogger())
	if !sm.CanHandle("service") {
		t.Fatal("expected CanHandle('service') to be true")
	}
	if sm.CanHandle("file") {
		t.Fatal("expected CanHandle('file') to be false")
	}
}

func TestServiceManager_StartStoppedService(t *testing.T) {
	mock := newMockExecutor()
	mock.activeState["nginx"] = false
	mock.enabledState["nginx"] = true

	sm := NewWithExecutor(testLogger(), mock)
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "nginx",
			Fields: map[string]interface{}{
				"name":    "nginx",
				"state":   "running",
				"enabled": "true",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("expected ActionUpdate, got %s", actions[0].ActionType)
	}
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}
	if actions[0].Description != "started" {
		t.Fatalf("expected description 'started', got %q", actions[0].Description)
	}
}

func TestServiceManager_StopRunningService(t *testing.T) {
	mock := newMockExecutor()
	mock.activeState["redis"] = true
	mock.enabledState["redis"] = false

	sm := NewWithExecutor(testLogger(), mock)
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "redis",
			Fields: map[string]interface{}{
				"name":    "redis",
				"state":   "stopped",
				"enabled": "false",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("expected ActionUpdate, got %s", actions[0].ActionType)
	}
	if actions[0].Description != "stopped" {
		t.Fatalf("expected 'stopped', got %q", actions[0].Description)
	}
}

func TestServiceManager_EnableDisabledService(t *testing.T) {
	mock := newMockExecutor()
	mock.activeState["sshd"] = true
	mock.enabledState["sshd"] = false

	sm := NewWithExecutor(testLogger(), mock)
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "sshd",
			Fields: map[string]interface{}{
				"name":    "sshd",
				"state":   "running",
				"enabled": "true",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].Description != "enabled" {
		t.Fatalf("expected 'enabled', got %q", actions[0].Description)
	}
}

func TestServiceManager_NoopWhenConverged(t *testing.T) {
	mock := newMockExecutor()
	mock.activeState["sshd"] = true
	mock.enabledState["sshd"] = true

	sm := NewWithExecutor(testLogger(), mock)
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "sshd",
			Fields: map[string]interface{}{
				"name":    "sshd",
				"state":   "running",
				"enabled": "true",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionNoop {
		t.Fatalf("expected ActionNoop, got %s", actions[0].ActionType)
	}
}

func TestServiceManager_StartFailure(t *testing.T) {
	mock := newMockExecutor()
	mock.activeState["broken"] = false
	mock.failOn = "start"

	sm := NewWithExecutor(testLogger(), mock)
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "broken",
			Fields: map[string]interface{}{
				"name":  "broken",
				"state": "running",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].Error == "" {
		t.Fatal("expected error on start failure")
	}
}

func TestServiceManager_EmptyName(t *testing.T) {
	sm := New(testLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind:   "service",
			Name:   "",
			Fields: map[string]interface{}{"name": ""},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].Error == "" {
		t.Fatal("expected error for empty name")
	}
}

func TestServiceManager_MultipleChanges(t *testing.T) {
	mock := newMockExecutor()
	mock.activeState["app"] = false
	mock.enabledState["app"] = false

	sm := NewWithExecutor(testLogger(), mock)
	specs := []reconciler.ResourceSpec{
		{
			Kind: "service",
			Name: "app",
			Fields: map[string]interface{}{
				"name":    "app",
				"state":   "running",
				"enabled": "true",
			},
		},
	}

	actions := sm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("expected ActionUpdate, got %s", actions[0].ActionType)
	}
	// Should report both started and enabled.
	if actions[0].Description != "started, enabled" {
		t.Fatalf("expected 'started, enabled', got %q", actions[0].Description)
	}
}
