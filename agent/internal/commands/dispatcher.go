// Package commands provides a command dispatcher that routes incoming commands
// from the controller to the appropriate handler.
package commands

import (
	"context"
	"fmt"
	"os"
	"strconv"

	"github.com/edgeguardian/agent/internal/comms"
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
func NewDispatcher(updater *ota.Updater, httpClient *comms.ControllerClient,
	deviceID, agentVersion string, logger *zap.Logger) *Dispatcher {

	d := &Dispatcher{
		handlers: make(map[string]Handler),
		logger:   logger,
	}

	d.Register("ota_update", d.handleOtaUpdate(updater, httpClient, deviceID, agentVersion))
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

func (d *Dispatcher) handleOtaUpdate(updater *ota.Updater, httpClient *comms.ControllerClient,
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

		// Helper to report status back to the controller.
		report := func(state string, progress int, errMsg string) {
			ctx := context.Background()
			_ = httpClient.ReportOTAStatus(ctx, &model.OTAStatusReport{
				DeploymentID: deploymentID,
				DeviceID:     deviceID,
				State:        state,
				Progress:     progress,
				ErrorMessage: errMsg,
			})
		}

		// Helper to report failure and clean up.
		fail := func(err error) error {
			report("failed", 0, err.Error())
			os.Remove(updater.StagingPath())
			return err
		}

		d.logger.Info("starting OTA update",
			zap.String("artifact", cmd.Params["artifact_name"]),
			zap.String("version", cmd.Params["artifact_version"]),
			zap.Int64("deployment_id", deploymentID))

		// 1. Download
		report("downloading", 10, "")
		fullURL := httpClient.BaseURL() + downloadURL
		stagingPath, err := updater.Download(fullURL)
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