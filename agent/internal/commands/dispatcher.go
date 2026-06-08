// Package commands provides a command dispatcher that routes incoming commands
// from the controller to the appropriate handler, with lifecycle hook support.
package commands

import (
	"context"
	"fmt"
	"time"

	"github.com/edgeguardian/agent/internal/comms"
	"github.com/edgeguardian/agent/internal/model"
	"go.uber.org/zap"
)

// Handler processes a single command and returns an error if it fails.
type Handler func(cmd model.Command) error

// Dispatcher routes commands by type to registered handlers.
type Dispatcher struct {
	handlers   map[string]Handler
	mqttClient *comms.MQTTClient
	deviceID   string
	logger     *zap.Logger
}

// NewDispatcher creates a new command dispatcher with default handlers.
func NewDispatcher(mqttClient *comms.MQTTClient,
	deviceID string, logger *zap.Logger) *Dispatcher {

	d := &Dispatcher{
		handlers:   make(map[string]Handler),
		mqttClient: mqttClient,
		deviceID:   deviceID,
		logger:     logger,
	}

	d.Register("restart", d.handleRestart())
	d.Register("script", d.handleScript())

	return d
}

// Register adds a handler for a command type.
func (d *Dispatcher) Register(cmdType string, handler Handler) {
	d.handlers[cmdType] = handler
}

// Dispatch routes a command to the appropriate handler, executing lifecycle hooks.
func (d *Dispatcher) Dispatch(cmd model.Command) error {
	d.logger.Info("dispatching command",
		zap.String("type", cmd.Type),
		zap.String("id", cmd.ID))

	// 1. Execute pre-hook if present.
	if cmd.Hooks != nil && cmd.Hooks.Pre != nil {
		ctx, cancel := d.contextWithTimeout(cmd)
		result := ExecuteScript(ctx, cmd.Hooks.Pre, d.deviceID)
		cancel()
		result.CommandID = cmd.ID
		result.Phase = "pre_hook"
		d.reportResult(result)
		if result.Status == "failed" || result.Status == "timeout" {
			d.logger.Error("pre-hook failed, aborting command",
				zap.String("id", cmd.ID), zap.String("error", result.ErrorMessage))
			return fmt.Errorf("pre-hook failed: %s", result.ErrorMessage)
		}
	}

	// 2. Execute main command.
	handler, ok := d.handlers[cmd.Type]
	if !ok {
		// If no registered handler but script is provided, fall back to script execution.
		if cmd.Script != nil {
			handler = d.handleScript()
		} else {
			d.logger.Warn("unknown command type", zap.String("type", cmd.Type), zap.String("id", cmd.ID))
			return fmt.Errorf("unknown command type: %s", cmd.Type)
		}
	}

	mainErr := handler(cmd)
	if mainErr != nil {
		d.logger.Error("command failed",
			zap.String("type", cmd.Type),
			zap.String("id", cmd.ID),
			zap.Error(mainErr))
	}

	// 3. Execute post-hook if present (always, even on failure).
	if cmd.Hooks != nil && cmd.Hooks.Post != nil {
		ctx, cancel := d.contextWithTimeout(cmd)
		result := ExecuteScript(ctx, cmd.Hooks.Post, d.deviceID)
		cancel()
		result.CommandID = cmd.ID
		result.Phase = "post_hook"
		d.reportResult(result)
	}

	return mainErr
}

func (d *Dispatcher) contextWithTimeout(cmd model.Command) (context.Context, context.CancelFunc) {
	if cmd.Timeout > 0 {
		return context.WithTimeout(context.Background(), time.Duration(cmd.Timeout)*time.Second)
	}
	return context.WithCancel(context.Background())
}

func (d *Dispatcher) reportResult(result *model.CommandResult) {
	if err := d.mqttClient.PublishCommandResult(result); err != nil {
		d.logger.Warn("failed to publish command result", zap.Error(err))
	}
}

// --- Built-in handlers ---

func (d *Dispatcher) handleRestart() Handler {
	return func(cmd model.Command) error {
		d.logger.Info("restart command received",
			zap.String("reason", cmd.Params["reason"]))
		return nil
	}
}

func (d *Dispatcher) handleScript() Handler {
	return func(cmd model.Command) error {
		if cmd.Script == nil {
			return fmt.Errorf("script command has no script spec")
		}

		ctx, cancel := d.contextWithTimeout(cmd)
		result := ExecuteScript(ctx, cmd.Script, d.deviceID)
		cancel()
		result.CommandID = cmd.ID
		result.Phase = "main"
		d.reportResult(result)

		if result.Status == "failed" || result.Status == "timeout" {
			return fmt.Errorf("script failed (exit %d): %s", result.ExitCode, result.ErrorMessage)
		}
		return nil
	}
}
