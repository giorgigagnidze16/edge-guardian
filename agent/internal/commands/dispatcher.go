// Package commands provides a command dispatcher that routes incoming commands
// from the controller to the appropriate handler, with lifecycle hook support.
package commands

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/edgeguardian/agent/internal/comms"
	"github.com/edgeguardian/agent/internal/model"
	"github.com/edgeguardian/agent/internal/ota"
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
func NewDispatcher(updater *ota.Updater, mqttClient *comms.MQTTClient,
	deviceID, agentVersion string, logger *zap.Logger) *Dispatcher {

	d := &Dispatcher{
		handlers:   make(map[string]Handler),
		mqttClient: mqttClient,
		deviceID:   deviceID,
		logger:     logger,
	}

	d.Register("ota_update", d.handleOtaUpdate(updater, deviceID, agentVersion))
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
		result := ExecuteScript(d.contextWithTimeout(cmd), cmd.Hooks.Pre, d.deviceID)
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
		result := ExecuteScript(d.contextWithTimeout(cmd), cmd.Hooks.Post, d.deviceID)
		result.CommandID = cmd.ID
		result.Phase = "post_hook"
		d.reportResult(result)
	}

	return mainErr
}

func (d *Dispatcher) contextWithTimeout(cmd model.Command) context.Context {
	if cmd.Timeout > 0 {
		ctx, _ := context.WithTimeout(context.Background(), time.Duration(cmd.Timeout)*time.Second)
		return ctx
	}
	return context.Background()
}

func (d *Dispatcher) reportResult(result *model.CommandResult) {
	if err := d.mqttClient.PublishCommandResult(result); err != nil {
		d.logger.Warn("failed to publish command result", zap.Error(err))
	}
}

// --- Built-in handlers ---

func (d *Dispatcher) handleOtaUpdate(updater *ota.Updater,
	deviceID, agentVersion string) Handler {

	return func(cmd model.Command) error {
		downloadURL := cmd.Params["download_url"]
		if downloadURL == "" {
			d.logger.Info("OTA update command received but no download URL")
			return nil
		}

		deploymentID, _ := strconv.ParseInt(cmd.Params["deployment_id"], 10, 64)
		sha256Hash := cmd.Params["sha256"]
		ed25519Sig := cmd.Params["ed25519_sig"]

		report := func(state string, progress int, errMsg string) {
			_ = d.mqttClient.PublishOTAStatus(&model.OTAStatusReport{
				DeploymentID: deploymentID,
				DeviceID:     deviceID,
				State:        state,
				Progress:     progress,
				ErrorMessage: errMsg,
			})
		}

		fail := func(err error) error {
			report("failed", 0, err.Error())
			os.Remove(updater.StagingPath())
			return err
		}

		d.logger.Info("starting OTA update",
			zap.String("artifact", cmd.Params["artifact_name"]),
			zap.String("version", cmd.Params["artifact_version"]),
			zap.Int64("deployment_id", deploymentID))

		// 1. Download (presigned URL — full URL, no prefix needed)
		report("downloading", 10, "")
		stagingPath, err := updater.Download(downloadURL)
		if err != nil {
			return fail(fmt.Errorf("download failed: %w", err))
		}

		// 2. Verify SHA-256
		report("verifying", 50, "")
		if sha256Hash != "" {
			if err := ota.VerifySHA256(stagingPath, sha256Hash); err != nil {
				return fail(fmt.Errorf("SHA-256 verification failed: %w", err))
			}
		}

		// 3. Verify Ed25519 signature (if provided and key configured)
		if ed25519Sig != "" && updater.Ed25519PublicKey() != nil {
			if err := ota.VerifyEd25519(stagingPath, ed25519Sig, updater.Ed25519PublicKey()); err != nil {
				return fail(fmt.Errorf("Ed25519 verification failed: %w", err))
			}
		}

		// 4. Write update marker for post-restart detection
		report("applying", 80, "")
		if err := updater.WriteUpdateMarker(deploymentID, agentVersion); err != nil {
			d.logger.Warn("failed to write update marker", zap.Error(err))
		}

		// 5. Apply — exits with code 42 for watchdog to swap binary
		return updater.Apply(stagingPath)
	}
}

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

		ctx := d.contextWithTimeout(cmd)
		result := ExecuteScript(ctx, cmd.Script, d.deviceID)
		result.CommandID = cmd.ID
		result.Phase = "main"
		d.reportResult(result)

		if result.Status == "failed" || result.Status == "timeout" {
			return fmt.Errorf("script failed (exit %d): %s", result.ExitCode, result.ErrorMessage)
		}
		return nil
	}
}
