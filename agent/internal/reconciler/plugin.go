package reconciler

import "context"

// ActionType classifies what the reconciler did.
type ActionType string

const (
	ActionCreate  ActionType = "create"
	ActionUpdate  ActionType = "update"
	ActionDelete  ActionType = "delete"
	ActionNoop    ActionType = "noop"
	ActionSkipped ActionType = "skipped"
)

// Action records a single reconciliation operation performed by a plugin.
type Action struct {
	Plugin      string     `json:"plugin"`
	Resource    string     `json:"resource"`   // e.g. file path or service name
	ActionType  ActionType `json:"actionType"`
	Description string     `json:"description"`
	Error       string     `json:"error,omitempty"`
}

// ResourceSpec is a generic desired-state entry that plugins know how to handle.
// Each plugin checks CanHandle and processes matching specs.
type ResourceSpec struct {
	Kind   string                 // "file", "service", etc.
	Name   string                 // identifier (path, service name)
	Fields map[string]interface{} // arbitrary spec fields
}

// Plugin is the interface that reconciler plugins must implement.
// Each plugin handles one kind of resource (files, services, packages, etc.).
type Plugin interface {
	// Name returns the plugin's identifier (e.g. "file_manager", "service_manager").
	Name() string

	// CanHandle returns true if the plugin can reconcile resources of the given kind.
	CanHandle(kind string) bool

	// Reconcile compares desired state against actual state and applies corrections.
	// It returns the list of actions taken (including no-ops for already-converged resources).
	Reconcile(ctx context.Context, specs []ResourceSpec) []Action
}
