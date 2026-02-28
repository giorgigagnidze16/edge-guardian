package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"syscall"
	"time"

	"github.com/edgeguardian/agent/internal/comms"
	"github.com/edgeguardian/agent/internal/config"
	"github.com/edgeguardian/agent/internal/health"
	"github.com/edgeguardian/agent/internal/model"
	"github.com/edgeguardian/agent/internal/reconciler"
	"github.com/edgeguardian/agent/internal/storage"
	"github.com/edgeguardian/agent/plugins/filemanager"
	"github.com/edgeguardian/agent/plugins/service"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

const agentVersion = "0.2.0"

func main() {
	configPath := flag.String("config", "/etc/edgeguardian/agent.yaml", "path to agent config file")
	flag.Parse()

	// Load configuration.
	cfg, err := config.Load(*configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to load config: %v\n", err)
		os.Exit(1)
	}

	// Initialize logger.
	logger := initLogger(cfg.LogLevel)
	defer logger.Sync()

	logger.Info("EdgeGuardian agent starting",
		zap.String("version", agentVersion),
		zap.String("device_id", cfg.DeviceID),
		zap.String("arch", runtime.GOARCH),
		zap.String("os", runtime.GOOS),
		zap.String("data_dir", cfg.DataDir),
	)

	// Ensure data directory exists.
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

	// Set up context with graceful shutdown.
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	// Initialize reconciler.
	rec := reconciler.New(cfg.ReconcileInterval, logger)

	// Register plugins.
	rec.RegisterPlugin(filemanager.New(logger))
	rec.RegisterPlugin(service.New(logger))

	// Load cached desired state from BoltDB (offline-first).
	var cachedManifest model.DeviceManifest
	found, err := store.LoadDesiredState("current", &cachedManifest)
	if err != nil {
		logger.Warn("failed to load cached desired state", zap.Error(err))
	} else if found {
		logger.Info("loaded cached desired state from BoltDB",
			zap.Int64("version", cachedManifest.Version),
		)
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

	// Create health collector.
	healthCollector := health.New()

	// Create and connect MQTT client.
	mqttClient := comms.NewMQTTClient(comms.MQTTConfig{
		BrokerURL: cfg.MQTT.BrokerURL,
		DeviceID:  cfg.DeviceID,
		Username:  cfg.MQTT.Username,
		Password:  cfg.MQTT.Password,
		TopicRoot: cfg.MQTT.TopicRoot,
		Store:     store,
	}, logger)

	// Set MQTT command handler.
	mqttClient.SetCommandHandler(func(cmd model.Command) {
		logger.Info("received command via MQTT",
			zap.String("id", cmd.ID),
			zap.String("type", cmd.Type),
		)
		// Phase 2: log commands. Phase 3+: dispatch to OTA, VPN, exec handlers.
	})

	if err := mqttClient.Connect(10 * time.Second); err != nil {
		logger.Warn("MQTT connection failed (will operate without telemetry)", zap.Error(err))
	}
	defer mqttClient.Close()

	// Start reconciler loop.
	go rec.Run(ctx)

	// Start heartbeat loop.
	go heartbeatLoop(ctx, httpClient, cfg.DeviceID, rec, store, healthCollector, logger)

	// Start MQTT telemetry loop.
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

	// Wait for shutdown signal.
	sig := <-sigCh
	logger.Info("received signal, shutting down", zap.String("signal", sig.String()))
	cancel()

	// Give goroutines time to clean up.
	time.Sleep(500 * time.Millisecond)
	logger.Info("agent stopped")
}

// registerWithController performs the initial registration with the controller.
// On failure, the agent continues with cached state (offline-first).
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

	// If registration returned an initial manifest, save it and apply.
	if resp.InitialManifest != nil {
		logger.Info("received initial manifest from controller",
			zap.Int64("version", resp.InitialManifest.Version),
		)
		rec.SetDesiredState(resp.InitialManifest)
		if err := store.SaveDesiredState("current", resp.InitialManifest); err != nil {
			logger.Error("failed to save initial manifest to BoltDB", zap.Error(err))
		}
	}
}

// heartbeatLoop sends periodic heartbeats to the controller. When the
// controller responds with an updated manifest, it is applied to the
// reconciler and persisted in BoltDB.
func heartbeatLoop(
	ctx context.Context,
	client *comms.ControllerClient,
	deviceID string,
	rec *reconciler.Reconciler,
	store *storage.Store,
	healthCollector *health.Collector,
	logger *zap.Logger,
) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// Collect health status for the heartbeat.
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

			// Apply manifest update if the controller signals one.
			if resp.ManifestUpdated && resp.Manifest != nil {
				logger.Info("received updated manifest via heartbeat",
					zap.Int64("version", resp.Manifest.Version),
				)
				rec.SetDesiredState(resp.Manifest)
				if err := store.SaveDesiredState("current", resp.Manifest); err != nil {
					logger.Error("failed to save manifest to BoltDB", zap.Error(err))
				}
			}

			// Log any pending commands (full dispatch in later phases).
			for _, cmd := range resp.PendingCommands {
				logger.Info("pending command from heartbeat",
					zap.String("id", cmd.ID),
					zap.String("type", cmd.Type),
				)
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
