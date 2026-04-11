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
	"github.com/edgeguardian/agent/plugins/certificate"
	"github.com/edgeguardian/agent/plugins/filemanager"
	"github.com/edgeguardian/agent/plugins/service"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

const agentVersion = "0.4.0"

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
	// Certificate plugin registered after MQTT client is created (needs requester callback).

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

	// Create and connect MQTT client (sole communication channel).
	mqttClient := comms.NewMQTTClient(comms.MQTTConfig{
		BrokerURL: cfg.MQTT.BrokerURL,
		DeviceID:  cfg.DeviceID,
		Username:  cfg.MQTT.Username,
		Password:  cfg.MQTT.Password,
		TopicRoot: cfg.MQTT.TopicRoot,
		Store:     store,
	}, logger)

	// Register certificate plugin with MQTT-backed requester.
	rec.RegisterPlugin(certificate.New(logger,
		func(name, cn string, sans []string, csrPEM []byte, reqType, serial string) error {
			return mqttClient.PublishCertRequest(name, cn, sans, csrPEM, reqType, serial)
		}, store))

	// Wire cert response handler: stores signed certs in BoltDB for the plugin to install.
	mqttClient.SetCertResponseHandler(func(name string, certPEM, caCertPEM []byte) {
		logger.Info("received signed certificate", zap.String("name", name))
		if err := store.SaveMeta("cert:"+name, string(certPEM)); err != nil {
			logger.Error("failed to save cert to BoltDB", zap.String("name", name), zap.Error(err))
		}
		if len(caCertPEM) > 0 {
			if err := store.SaveMeta("ca_cert", string(caCertPEM)); err != nil {
				logger.Error("failed to save CA cert to BoltDB", zap.Error(err))
			}
		}
	})

	// Wire desired state handler: updates reconciler + persists to BoltDB.
	mqttClient.SetDesiredStateHandler(func(manifest *model.DeviceManifest) {
		logger.Info("received desired state update",
			zap.Int64("version", manifest.Version))
		rec.SetDesiredState(manifest)
		if err := store.SaveDesiredState("current", manifest); err != nil {
			logger.Error("failed to save manifest to BoltDB", zap.Error(err))
		}
	})

	if err := mqttClient.Connect(10 * time.Second); err != nil {
		logger.Warn("MQTT connection failed (will retry via auto-reconnect)", zap.Error(err))
	}
	defer mqttClient.Close()

	// Enroll with controller via MQTT (or load existing device token).
	enrollViaMMQTT(cfg, mqttClient, rec, store, logger)

	// Create OTA updater and command dispatcher.
	updater := ota.NewUpdater(cfg.DataDir, cfg.OTA.SignKey, logger)
	dispatcher := commands.NewDispatcher(updater, mqttClient, cfg.DeviceID, agentVersion, logger)

	// Set command handler on MQTT client.
	mqttClient.SetCommandHandler(func(cmd model.Command) {
		if err := dispatcher.Dispatch(cmd); err != nil {
			logger.Error("command dispatch failed",
				zap.String("id", cmd.ID),
				zap.String("type", cmd.Type),
				zap.Error(err))
		}
	})

	// Check for post-update or rollback markers.
	checkPostUpdateStatus(updater, mqttClient, cfg.DeviceID, logger)

	// Start local health server for watchdog probing.
	go startHealthServer(cfg.Health.Port, logger)

	// Create health collector.
	healthCollector := health.New(cfg.Health.DiskPath)

	// Start reconciler loop.
	go rec.Run(ctx)

	// Start heartbeat loop (via MQTT).
	go mqttClient.RunHeartbeatLoop(ctx, 30*time.Second, func() *model.HeartbeatMessage {
		status := healthCollector.Collect()
		status.ReconcileStatus = rec.Status()
		lastRec := rec.LastReconcile()
		if !lastRec.IsZero() {
			status.LastReconcile = lastRec.Format(time.RFC3339)
		}
		return &model.HeartbeatMessage{
			DeviceID:        cfg.DeviceID,
			AgentVersion:    agentVersion,
			Status:          status,
			ManifestVersion: rec.ManifestVersion(),
			Timestamp:       time.Now(),
		}
	})

	// Start telemetry loop (via MQTT).
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
// the final OTA status back to the controller via MQTT.
func checkPostUpdateStatus(updater *ota.Updater,
	mqttClient *comms.MQTTClient, deviceID string, logger *zap.Logger) {

	// Check rollback marker first (takes priority).
	rollbackExists, _ := updater.ReadRollbackMarker()
	if rollbackExists {
		logger.Warn("rollback marker detected — previous OTA update was rolled back")

		if marker, err := updater.ReadUpdateMarker(); err == nil {
			_ = mqttClient.PublishOTAStatus(&model.OTAStatusReport{
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
		return // No marker = not a post-update restart.
	}

	logger.Info("update marker detected — OTA update completed successfully",
		zap.Int64("deployment_id", marker.DeploymentID),
		zap.String("previous_version", marker.PreviousVersion))

	_ = mqttClient.PublishOTAStatus(&model.OTAStatusReport{
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

// tokenFilePath returns the path where the device token is persisted.
func tokenFilePath(cfg *config.Config) string {
	if cfg.Auth.TokenFile != "" {
		return cfg.Auth.TokenFile
	}
	return filepath.Join(cfg.DataDir, "device-token")
}

func enrollViaMMQTT(
	cfg *config.Config,
	mqttClient *comms.MQTTClient,
	rec *reconciler.Reconciler,
	store *storage.Store,
	logger *zap.Logger,
) {
	tokenPath := tokenFilePath(cfg)

	// 1. Check for existing device token on disk.
	if tokenData, err := os.ReadFile(tokenPath); err == nil && len(tokenData) > 0 {
		logger.Info("loaded device token from file, skipping enrollment",
			zap.String("token_file", tokenPath))
		return
	}

	// 2. Enroll with enrollment token via MQTT.
	if cfg.Auth.EnrollmentToken == "" {
		logger.Fatal("no device token file and no enrollment_token configured — cannot enroll")
	}

	hostname, _ := os.Hostname()

	resp, err := mqttClient.PublishEnrollRequest(&model.EnrollRequest{
		EnrollmentToken: cfg.Auth.EnrollmentToken,
		DeviceID:        cfg.DeviceID,
		Hostname:        hostname,
		Architecture:    runtime.GOARCH,
		OS:              runtime.GOOS,
		AgentVersion:    agentVersion,
		Labels:          cfg.Labels,
	})
	if err != nil {
		logger.Fatal("enrollment failed", zap.Error(err))
	}

	if !resp.Accepted {
		logger.Fatal("enrollment rejected by controller", zap.String("message", resp.Message))
	}

	logger.Info("enrolled with controller via MQTT", zap.String("message", resp.Message))

	// 3. Persist device token.
	if resp.DeviceToken != "" {
		if err := os.WriteFile(tokenPath, []byte(resp.DeviceToken), 0600); err != nil {
			logger.Error("failed to save device token to file", zap.Error(err))
		} else {
			logger.Info("device token saved", zap.String("token_file", tokenPath))
		}
	}

	// 4. Apply initial manifest if provided.
	if resp.InitialManifest != nil {
		logger.Info("received initial manifest from controller",
			zap.Int64("version", resp.InitialManifest.Version))
		rec.SetDesiredState(resp.InitialManifest)
		if err := store.SaveDesiredState("current", resp.InitialManifest); err != nil {
			logger.Error("failed to save initial manifest to BoltDB", zap.Error(err))
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
