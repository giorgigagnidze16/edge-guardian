// Package reconciler implements the core desired-state reconciliation loop.
// It periodically compares the declared manifest against actual device state
// and invokes registered plugins to correct any drift.
package reconciler

import (
	"context"
	"sync"
	"time"

	"github.com/edgeguardian/agent/internal/model"
	"go.uber.org/zap"
)

// Reconciler compares desired state to actual state and applies corrections
// by delegating to registered plugins.
type Reconciler struct {
	interval       time.Duration
	logger         *zap.Logger
	plugins        []Plugin
	desiredState   *model.DeviceManifest
	mu             sync.RWMutex
	lastReconcile  time.Time
	reconcileCount int64
	status         string   // "converged", "drifted", "error"
	lastActions    []Action // actions from the most recent reconcile cycle
}

// New creates a new Reconciler with the given interval.
func New(intervalSeconds int, logger *zap.Logger) *Reconciler {
	return &Reconciler{
		interval: time.Duration(intervalSeconds) * time.Second,
		logger:   logger,
		status:   "converged",
	}
}

// RegisterPlugin adds a plugin to the reconciler's plugin chain.
// Plugins are invoked in registration order during each reconcile cycle.
func (r *Reconciler) RegisterPlugin(p Plugin) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.plugins = append(r.plugins, p)
	r.logger.Info("plugin registered", zap.String("plugin", p.Name()))
}

// SetDesiredState updates the desired state manifest.
func (r *Reconciler) SetDesiredState(manifest *model.DeviceManifest) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.desiredState = manifest
	r.logger.Info("desired state updated",
		zap.Int64("version", manifest.Version),
	)
}

// GetDesiredState returns the current desired state.
func (r *Reconciler) GetDesiredState() *model.DeviceManifest {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.desiredState
}

// ManifestVersion returns the version of the current desired state manifest, or 0 if none.
func (r *Reconciler) ManifestVersion() int64 {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if r.desiredState == nil {
		return 0
	}
	return r.desiredState.Version
}

// Status returns the current reconciliation status.
func (r *Reconciler) Status() string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.status
}

// LastReconcile returns the time of the last reconciliation.
func (r *Reconciler) LastReconcile() time.Time {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.lastReconcile
}

// LastActions returns the actions from the most recent reconcile cycle.
func (r *Reconciler) LastActions() []Action {
	r.mu.RLock()
	defer r.mu.RUnlock()
	result := make([]Action, len(r.lastActions))
	copy(result, r.lastActions)
	return result
}

// Run starts the reconciliation loop. Blocks until context is cancelled.
func (r *Reconciler) Run(ctx context.Context) {
	r.logger.Info("reconciler started", zap.Duration("interval", r.interval))

	ticker := time.NewTicker(r.interval)
	defer ticker.Stop()

	// Run immediately on start.
	r.reconcileOnce(ctx)

	for {
		select {
		case <-ctx.Done():
			r.logger.Info("reconciler stopped")
			return
		case <-ticker.C:
			r.reconcileOnce(ctx)
		}
	}
}

func (r *Reconciler) reconcileOnce(ctx context.Context) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.reconcileCount++
	r.lastReconcile = time.Now()

	if r.desiredState == nil {
		r.logger.Debug("no desired state set, skipping reconciliation")
		r.status = "converged"
		r.lastActions = nil
		return
	}

	// Diff the manifest into categorized resource specs.
	diff := Diff(r.desiredState)
	if !diff.HasResources() {
		r.status = "converged"
		r.lastActions = nil
		return
	}

	r.logger.Info("reconciling",
		zap.Int64("cycle", r.reconcileCount),
		zap.Int("files", len(diff.FileSpecs)),
		zap.Int("services", len(diff.ServiceSpecs)),
	)

	var allActions []Action
	hasErrors := false

	// Dispatch specs to matching plugins.
	for _, plugin := range r.plugins {
		var specsForPlugin []ResourceSpec
		for _, spec := range diff.AllSpecs() {
			if plugin.CanHandle(spec.Kind) {
				specsForPlugin = append(specsForPlugin, spec)
			}
		}
		if len(specsForPlugin) == 0 {
			continue
		}

		r.logger.Debug("dispatching to plugin",
			zap.String("plugin", plugin.Name()),
			zap.Int("specs", len(specsForPlugin)),
		)

		actions := plugin.Reconcile(ctx, specsForPlugin)
		for _, a := range actions {
			if a.Error != "" {
				hasErrors = true
				r.logger.Error("plugin action failed",
					zap.String("plugin", a.Plugin),
					zap.String("resource", a.Resource),
					zap.String("error", a.Error),
				)
			} else if a.ActionType != ActionNoop {
				r.logger.Info("plugin action applied",
					zap.String("plugin", a.Plugin),
					zap.String("resource", a.Resource),
					zap.String("action", string(a.ActionType)),
					zap.String("desc", a.Description),
				)
			}
		}
		allActions = append(allActions, actions...)
	}

	r.lastActions = allActions

	if hasErrors {
		r.status = "error"
	} else {
		r.status = "converged"
	}

	r.logger.Debug("reconciliation complete",
		zap.Int64("cycle", r.reconcileCount),
		zap.String("status", r.status),
		zap.Int("actions", len(allActions)),
	)
}
