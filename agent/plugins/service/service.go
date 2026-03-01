// Package service implements a reconciler plugin that manages system services.
// It ensures services are in the desired state (running/stopped) and enabled/disabled
// as declared in the device manifest. Platform-specific executors are in
// executor_linux.go, executor_windows.go, and executor_darwin.go.
package service

import (
	"context"
	"fmt"
	"strings"

	"github.com/edgeguardian/agent/internal/reconciler"
	"go.uber.org/zap"
)

// ServiceExecutor abstracts platform-specific service management commands.
type ServiceExecutor interface {
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

// ServiceManager reconciles service resources using the platform executor.
type ServiceManager struct {
	logger   *zap.Logger
	executor ServiceExecutor
}

// New creates a ServiceManager plugin with the platform-native executor.
func New(logger *zap.Logger) *ServiceManager {
	return &ServiceManager{
		logger:   logger,
		executor: newPlatformExecutor(),
	}
}

// NewWithExecutor creates a ServiceManager with a custom executor (for testing).
func NewWithExecutor(logger *zap.Logger, executor ServiceExecutor) *ServiceManager {
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
