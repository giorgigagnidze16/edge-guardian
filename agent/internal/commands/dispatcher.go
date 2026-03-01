// Package commands provides a command dispatcher that routes incoming commands
// from the controller to the appropriate handler.
package commands

import (
	"fmt"

	"github.com/edgeguardian/agent/internal/model"
	"github.com/edgeguardian/agent/internal/ota"
	"go.uber.org/zap"
)

// Handler processes a single command and returns an error if it fails.
type Handler func(cmd model.Command) error

// Dispatcher routes commands by type to registered handlers.
type Dispatcher struct {
	handlers map[string]Handler
	logger   *zap.Logger
}

// NewDispatcher creates a new command dispatcher with default handlers.
func NewDispatcher(updater *ota.Updater, logger *zap.Logger) *Dispatcher {
	d := &Dispatcher{
		handlers: make(map[string]Handler),
		logger:   logger,
	}

	d.Register("ota_update", d.handleOtaUpdate(updater))
	d.Register("restart", d.handleRestart())

	return d
}

// Register adds a handler for a command type.
func (d *Dispatcher) Register(cmdType string, handler Handler) {
	d.handlers[cmdType] = handler
}

// Dispatch routes a command to the appropriate handler.
func (d *Dispatcher) Dispatch(cmd model.Command) error {
	handler, ok := d.handlers[cmd.Type]
	if !ok {
		d.logger.Warn("unknown command type", zap.String("type", cmd.Type), zap.String("id", cmd.ID))
		return fmt.Errorf("unknown command type: %s", cmd.Type)
	}

	d.logger.Info("dispatching command",
		zap.String("type", cmd.Type),
		zap.String("id", cmd.ID))

	if err := handler(cmd); err != nil {
		d.logger.Error("command failed",
			zap.String("type", cmd.Type),
			zap.String("id", cmd.ID),
			zap.Error(err))
		return err
	}

	return nil
}

func (d *Dispatcher) handleOtaUpdate(updater *ota.Updater) Handler {
	return func(cmd model.Command) error {
		sha256Hash := cmd.Params["sha256"]
		downloadURL := cmd.Params["download_url"]

		if downloadURL == "" {
			d.logger.Info("OTA update command received but no download URL (controller-managed artifact)")
			return nil
		}

		d.logger.Info("starting OTA update",
			zap.String("artifact", cmd.Params["artifact_name"]),
			zap.String("version", cmd.Params["artifact_version"]))

		// Download
		stagingPath, err := updater.Download(downloadURL)
		if err != nil {
			return fmt.Errorf("download failed: %w", err)
		}

		// Verify SHA-256
		if sha256Hash != "" {
			if err := ota.VerifySHA256(stagingPath, sha256Hash); err != nil {
				return fmt.Errorf("verification failed: %w", err)
			}
		}

		// Apply (exits with code 42)
		return updater.Apply(stagingPath)
	}
}

func (d *Dispatcher) handleRestart() Handler {
	return func(cmd model.Command) error {
		d.logger.Info("restart command received",
			zap.String("reason", cmd.Params["reason"]))
		// Exit cleanly — the watchdog will restart us
		// Use exit code 0 for clean restart (not 42 which means binary swap)
		return nil
	}
}
