package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"time"

	"github.com/edgeguardian/agent/internal/commands"
	"github.com/edgeguardian/agent/internal/comms"
	"github.com/edgeguardian/agent/internal/config"
	"github.com/edgeguardian/agent/internal/health"
	"github.com/edgeguardian/agent/internal/model"
	"github.com/edgeguardian/agent/internal/ota"
	"github.com/edgeguardian/agent/internal/reconciler"
	"github.com/edgeguardian/agent/internal/storage"
	"github.com/edgeguardian/agent/plugins/filemanager"
	"github.com/edgeguardian/agent/plugins/service"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

const agentVersion = "0.3.0"

func main() {
	configPath := flag.String("config", config.DefaultConfigPath, "path to agent config file")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to load config: %v\n", err)
		os.Exit(1)
	}

	logger := initLogger(cfg.LogLevel)
	defer logger.Sync()

	logger.Info("EdgeGuardian agent starting",
		zap.String("version", agentVersion),
		zap.String("device_id", cfg.DeviceID),
		zap.String("arch", runtime.GOARCH),
		zap.String("os", runtime.GOOS),
		zap.String("data_dir", cfg.DataDir),
	)

	if err := os.MkdirAll(cfg.DataDir, 0750); err != nil {
		logger.Fatal("failed to create data directory", zap.Error(err))
	}

	// Open BoltDB storage (offline-first persistence).
	dbPath := filepath.Join(cfg.DataDir, "agent.db")
	store, err := storage.Open(dbPath)
	if err != nil {
		logger.Fatal("failed to open BoltDB", zap.String("path", dbPath), zap.Error(err))
	}
	defer func() {
		if err := store.Close(); err != nil {
			logger.Error("failed to close BoltDB", zap.Error(err))
		}
		logger.Info("BoltDB closed")
	}()
	logger.Info("BoltDB opened", zap.String("path", dbPath))

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, platformSignals()...)

	// Initialize reconciler with plugins.
	rec := reconciler.New(cfg.ReconcileInterval, logger)
	rec.RegisterPlugin(filemanager.New(logger))
	rec.RegisterPlugin(service.New(logger))

	// Load cached desired state from BoltDB (offline-first).
	var cachedManifest model.DeviceManifest
	found, err := store.LoadDesiredState("current", &cachedManifest)
	if err != nil {
		logger.Warn("failed to load cached desired state", zap.Error(err))
	} else if found {
		logger.Info("loaded cached desired state from BoltDB",
			zap.Int64("version", cachedManifest.Version))
		rec.SetDesiredState(&cachedManifest)
	}

	// Create HTTP controller client.
	httpClient := comms.NewControllerClient(cfg.ControllerAddress, cfg.ControllerPort, logger)
	defer func() {
		if err := httpClient.Close(); err != nil {
			logger.Error("failed to close HTTP client", zap.Error(err))
		}
	}()

	// Register with controller.
	registerWithController(ctx, cfg, httpClient, rec, store, logger)

	// Create OTA updater and command dispatcher.
	updater := ota.NewUpdater(cfg.DataDir, cfg.OTA.SignKey, logger)
	dispatcher := commands.NewDispatcher(updater, httpClient, cfg.DeviceID, agentVersion, logger)

	// Check for post-update or rollback markers.
	checkPostUpdateStatus(ctx, updater, httpClient, cfg.DeviceID, logger)

	// Start local health server for watchdog probing.
	go startHealthServer(cfg.Health.Port, logger)

	// Create health collector.
	healthCollector := health.New(cfg.Health.DiskPath)

	// Create and connect MQTT client.
	mqttClient := comms.NewMQTTClient(comms.MQTTConfig{
		BrokerURL: cfg.MQTT.BrokerURL,
		DeviceID:  cfg.DeviceID,
		Username:  cfg.MQTT.Username,
		Password:  cfg.MQTT.Password,
		TopicRoot: cfg.MQTT.TopicRoot,
		Store:     store,
	}, logger)

	mqttClient.SetCommandHandler(func(cmd model.Command) {
		if err := dispatcher.Dispatch(cmd); err != nil {
			logger.Error("command dispatch failed",
				zap.String("id", cmd.ID),
				zap.String("type", cmd.Type),
				zap.Error(err))
		}
	})

	if err := mqttClient.Connect(10 * time.Second); err != nil {
		logger.Warn("MQTT connection failed (will operate without telemetry)", zap.Error(err))
	}
	defer mqttClient.Close()

	go rec.Run(ctx)
	go heartbeatLoop(ctx, httpClient, cfg.DeviceID, rec, store, healthCollector, dispatcher, logger)
	go mqttClient.RunTelemetryLoop(ctx, 30*time.Second, func() *model.DeviceStatus {
		status := healthCollector.Collect()
		status.ReconcileStatus = rec.Status()
		lastRec := rec.LastReconcile()
		if !lastRec.IsZero() {
			status.LastReconcile = lastRec.Format(time.RFC3339)
		}
		return status
	})

	logger.Info("agent running, waiting for shutdown signal")

	sig := <-sigCh
	logger.Info("received signal, shutting down", zap.String("signal", sig.String()))
	cancel()
	time.Sleep(500 * time.Millisecond)
	logger.Info("agent stopped")
}

// checkPostUpdateStatus detects post-update or rollback markers and reports
// the final OTA status back to the controller.
func checkPostUpdateStatus(ctx context.Context, updater *ota.Updater,
	httpClient *comms.ControllerClient, deviceID string, logger *zap.Logger) {

	// Check rollback marker first (takes priority).
	rollbackExists, _ := updater.ReadRollbackMarker()
	if rollbackExists {
		logger.Warn("rollback marker detected — previous OTA update was rolled back")

		// Read the update marker to get deploymentID for reporting.
		if marker, err := updater.ReadUpdateMarker(); err == nil {
			reportCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			defer cancel()
			_ = httpClient.ReportOTAStatus(reportCtx, &model.OTAStatusReport{
				DeploymentID: marker.DeploymentID,
				DeviceID:     deviceID,
				State:        "rolled_back",
				Progress:     0,
				ErrorMessage: "agent crashed after update, rolled back to " + marker.PreviousVersion,
			})
			_ = updater.ClearUpdateMarker()
		}
		_ = updater.ClearRollbackMarker()
		return
	}

	// Check update marker (successful update).
	marker, err := updater.ReadUpdateMarker()
	if err != nil {
		return // No marker = not a post-update restart
	}

	logger.Info("update marker detected — OTA update completed successfully",
		zap.Int64("deployment_id", marker.DeploymentID),
		zap.String("previous_version", marker.PreviousVersion))

	reportCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	_ = httpClient.ReportOTAStatus(reportCtx, &model.OTAStatusReport{
		DeploymentID: marker.DeploymentID,
		DeviceID:     deviceID,
		State:        "completed",
		Progress:     100,
	})
	_ = updater.ClearUpdateMarker()
}

// startHealthServer runs a lightweight HTTP health endpoint on localhost only.
func startHealthServer(port int, logger *zap.Logger) {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{
			"status":  "ok",
			"version": agentVersion,
		})
	})

	addr := fmt.Sprintf("127.0.0.1:%d", port)
	logger.Info("health server starting", zap.String("addr", addr))

	listener, err := net.Listen("tcp", addr)
	if err != nil {
		logger.Warn("health server failed to start", zap.Error(err))
		return
	}

	server := &http.Server{Handler: mux}
	if err := server.Serve(listener); err != nil && err != http.ErrServerClosed {
		logger.Warn("health server error", zap.Error(err))
	}
}

func registerWithController(
	ctx context.Context,
	cfg *config.Config,
	client *comms.ControllerClient,
	rec *reconciler.Reconciler,
	store *storage.Store,
	logger *zap.Logger,
) {
	hostname, _ := os.Hostname()

	regCtx, regCancel := context.WithTimeout(ctx, 15*time.Second)
	defer regCancel()

	resp, err := client.Register(regCtx, &model.RegisterRequest{
		DeviceID:     cfg.DeviceID,
		Hostname:     hostname,
		Architecture: runtime.GOARCH,
		OS:           runtime.GOOS,
		AgentVersion: agentVersion,
		Labels:       cfg.Labels,
	})
	if err != nil {
		logger.Warn("registration failed (controller may be offline, using cached state)", zap.Error(err))
		return
	}

	if !resp.Accepted {
		logger.Warn("registration rejected by controller", zap.String("message", resp.Message))
		return
	}

	logger.Info("registered with controller", zap.String("message", resp.Message))

	if resp.InitialManifest != nil {
		logger.Info("received initial manifest from controller",
			zap.Int64("version", resp.InitialManifest.Version))
		rec.SetDesiredState(resp.InitialManifest)
		if err := store.SaveDesiredState("current", resp.InitialManifest); err != nil {
			logger.Error("failed to save initial manifest to BoltDB", zap.Error(err))
		}
	}
}

func heartbeatLoop(
	ctx context.Context,
	client *comms.ControllerClient,
	deviceID string,
	rec *reconciler.Reconciler,
	store *storage.Store,
	healthCollector *health.Collector,
	dispatcher *commands.Dispatcher,
	logger *zap.Logger,
) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			status := healthCollector.Collect()
			status.ReconcileStatus = rec.Status()
			lastRec := rec.LastReconcile()
			if !lastRec.IsZero() {
				status.LastReconcile = lastRec.Format(time.RFC3339)
			}

			hbCtx, hbCancel := context.WithTimeout(ctx, 10*time.Second)
			resp, err := client.Heartbeat(hbCtx, &model.HeartbeatRequest{
				DeviceID:     deviceID,
				AgentVersion: agentVersion,
				Status:       status,
				Timestamp:    time.Now(),
			})
			hbCancel()

			if err != nil {
				logger.Warn("heartbeat failed", zap.Error(err))
				continue
			}

			if resp.ManifestUpdated && resp.Manifest != nil {
				logger.Info("received updated manifest via heartbeat",
					zap.Int64("version", resp.Manifest.Version))
				rec.SetDesiredState(resp.Manifest)
				if err := store.SaveDesiredState("current", resp.Manifest); err != nil {
					logger.Error("failed to save manifest to BoltDB", zap.Error(err))
				}
			}

			for _, cmd := range resp.PendingCommands {
				if err := dispatcher.Dispatch(cmd); err != nil {
					logger.Error("heartbeat command dispatch failed",
						zap.String("id", cmd.ID),
						zap.String("type", cmd.Type),
						zap.Error(err))
				}
			}
		}
	}
}

func initLogger(level string) *zap.Logger {
	var zapLevel zapcore.Level
	switch level {
	case "debug":
		zapLevel = zapcore.DebugLevel
	case "warn":
		zapLevel = zapcore.WarnLevel
	case "error":
		zapLevel = zapcore.ErrorLevel
	default:
		zapLevel = zapcore.InfoLevel
	}

	cfg := zap.Config{
		Level:            zap.NewAtomicLevelAt(zapLevel),
		Development:      false,
		Encoding:         "json",
		EncoderConfig:    zap.NewProductionEncoderConfig(),
		OutputPaths:      []string{"stdout"},
		ErrorOutputPaths: []string{"stderr"},
	}

	logger, err := cfg.Build()
	if err != nil {
		panic(fmt.Sprintf("failed to initialize logger: %v", err))
	}

	return logger
}
