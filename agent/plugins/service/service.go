// Package service implements a reconciler plugin that manages systemd services.
// It ensures services are in the desired state (running/stopped) and enabled/disabled
// as declared in the device manifest.
package service

import (
	"context"
	"fmt"
	"os/exec"
	"strings"

	"github.com/edgeguardian/agent/internal/reconciler"
	"go.uber.org/zap"
)

// SystemdExecutor abstracts systemctl commands for testability.
type SystemdExecutor interface {
	// IsActive returns true if the service is currently running.
	IsActive(ctx context.Context, name string) (bool, error)
	// IsEnabled returns true if the service is enabled at boot.
	IsEnabled(ctx context.Context, name string) (bool, error)
	// Start starts the service.
	Start(ctx context.Context, name string) error
	// Stop stops the service.
	Stop(ctx context.Context, name string) error
	// Enable enables the service at boot.
	Enable(ctx context.Context, name string) error
	// Disable disables the service at boot.
	Disable(ctx context.Context, name string) error
}

// RealSystemd executes actual systemctl commands.
type RealSystemd struct{}

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

// ServiceManager reconciles systemd service resources.
type ServiceManager struct {
	logger   *zap.Logger
	executor SystemdExecutor
}

// New creates a ServiceManager plugin with real systemd execution.
func New(logger *zap.Logger) *ServiceManager {
	return &ServiceManager{
		logger:   logger,
		executor: &RealSystemd{},
	}
}

// NewWithExecutor creates a ServiceManager with a custom executor (for testing).
func NewWithExecutor(logger *zap.Logger, executor SystemdExecutor) *ServiceManager {
	return &ServiceManager{
		logger:   logger,
		executor: executor,
	}
}

// Name returns the plugin identifier.
func (sm *ServiceManager) Name() string {
	return "service_manager"
}

// CanHandle returns true for "service" resources.
func (sm *ServiceManager) CanHandle(kind string) bool {
	return kind == "service"
}

// Reconcile checks each service's actual state and applies corrections.
func (sm *ServiceManager) Reconcile(ctx context.Context, specs []reconciler.ResourceSpec) []reconciler.Action {
	var actions []reconciler.Action

	for _, spec := range specs {
		select {
		case <-ctx.Done():
			actions = append(actions, reconciler.Action{
				Plugin:      sm.Name(),
				Resource:    spec.Name,
				ActionType:  reconciler.ActionSkipped,
				Description: "context cancelled",
			})
			return actions
		default:
		}

		action := sm.reconcileService(ctx, spec)
		actions = append(actions, action)
	}

	return actions
}

func (sm *ServiceManager) reconcileService(ctx context.Context, spec reconciler.ResourceSpec) reconciler.Action {
	name, _ := spec.Fields["name"].(string)
	desiredState, _ := spec.Fields["state"].(string)
	desiredEnabled, _ := spec.Fields["enabled"].(string)

	if name == "" {
		return reconciler.Action{
			Plugin:     sm.Name(),
			Resource:   spec.Name,
			ActionType: reconciler.ActionSkipped,
			Error:      "empty service name",
		}
	}

	var changes []string

	// Check and reconcile running state.
	if desiredState != "" {
		isActive, err := sm.executor.IsActive(ctx, name)
		if err != nil {
			return reconciler.Action{
				Plugin:     sm.Name(),
				Resource:   name,
				ActionType: reconciler.ActionSkipped,
				Error:      fmt.Sprintf("check active state: %v", err),
			}
		}

		wantRunning := desiredState == "running"
		if wantRunning && !isActive {
			if err := sm.executor.Start(ctx, name); err != nil {
				return reconciler.Action{
					Plugin:     sm.Name(),
					Resource:   name,
					ActionType: reconciler.ActionUpdate,
					Error:      fmt.Sprintf("start service: %v", err),
				}
			}
			changes = append(changes, "started")
		} else if !wantRunning && isActive {
			if err := sm.executor.Stop(ctx, name); err != nil {
				return reconciler.Action{
					Plugin:     sm.Name(),
					Resource:   name,
					ActionType: reconciler.ActionUpdate,
					Error:      fmt.Sprintf("stop service: %v", err),
				}
			}
			changes = append(changes, "stopped")
		}
	}

	// Check and reconcile enabled state.
	if desiredEnabled != "" {
		isEnabled, err := sm.executor.IsEnabled(ctx, name)
		if err != nil {
			return reconciler.Action{
				Plugin:     sm.Name(),
				Resource:   name,
				ActionType: reconciler.ActionSkipped,
				Error:      fmt.Sprintf("check enabled state: %v", err),
			}
		}

		wantEnabled := desiredEnabled == "true"
		if wantEnabled && !isEnabled {
			if err := sm.executor.Enable(ctx, name); err != nil {
				return reconciler.Action{
					Plugin:     sm.Name(),
					Resource:   name,
					ActionType: reconciler.ActionUpdate,
					Error:      fmt.Sprintf("enable service: %v", err),
				}
			}
			changes = append(changes, "enabled")
		} else if !wantEnabled && isEnabled {
			if err := sm.executor.Disable(ctx, name); err != nil {
				return reconciler.Action{
					Plugin:     sm.Name(),
					Resource:   name,
					ActionType: reconciler.ActionUpdate,
					Error:      fmt.Sprintf("disable service: %v", err),
				}
			}
			changes = append(changes, "disabled")
		}
	}

	if len(changes) == 0 {
		return reconciler.Action{
			Plugin:      sm.Name(),
			Resource:    name,
			ActionType:  reconciler.ActionNoop,
			Description: "service already in desired state",
		}
	}

	return reconciler.Action{
		Plugin:      sm.Name(),
		Resource:    name,
		ActionType:  reconciler.ActionUpdate,
		Description: strings.Join(changes, ", "),
	}
}
