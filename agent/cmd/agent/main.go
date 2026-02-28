package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"github.com/edgeguardian/agent/internal/comms"
	"github.com/edgeguardian/agent/internal/config"
	"github.com/edgeguardian/agent/internal/reconciler"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

const agentVersion = "0.1.0"

func main() {
	configPath := flag.String("config", "/etc/edgeguardian/agent.yaml", "path to agent config file")
	flag.Parse()

	// Load configuration
	cfg, err := config.Load(*configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to load config: %v\n", err)
		os.Exit(1)
	}

	// Initialize logger
	logger := initLogger(cfg.LogLevel)
	defer logger.Sync()

	logger.Info("EdgeGuardian agent starting",
		zap.String("version", agentVersion),
		zap.String("device_id", cfg.DeviceID),
		zap.String("arch", runtime.GOARCH),
		zap.String("os", runtime.GOOS),
	)

	// Set up context with graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	// Connect to controller via gRPC
	grpcClient, err := comms.NewGRPCClient(cfg.ControllerAddress, cfg.GRPCPort, logger)
	if err != nil {
		logger.Fatal("failed to create gRPC client", zap.Error(err))
	}
	defer grpcClient.Close()

	// Register with controller
	regCtx, regCancel := context.WithTimeout(ctx, 10*time.Second)
	resp, err := grpcClient.Register(regCtx, cfg.DeviceID, agentVersion, cfg.Labels)
	regCancel()
	if err != nil {
		logger.Warn("initial registration failed (controller may be offline)", zap.Error(err))
	} else if resp.GetAccepted() {
		logger.Info("registered with controller", zap.String("message", resp.GetMessage()))
	} else {
		logger.Warn("registration rejected", zap.String("message", resp.GetMessage()))
	}

	// Initialize reconciler
	rec := reconciler.New(cfg.ReconcileInterval, logger)

	// If registration returned an initial manifest, use it
	if resp != nil && resp.GetInitialManifest() != nil {
		rec.SetDesiredState(resp.GetInitialManifest())
	}

	// Start reconciler in background
	go rec.Run(ctx)

	// Start heartbeat loop in background
	go heartbeatLoop(ctx, grpcClient, cfg.DeviceID, rec, logger)

	logger.Info("agent running, press Ctrl+C to stop")

	// Wait for shutdown signal
	sig := <-sigCh
	logger.Info("received signal, shutting down", zap.String("signal", sig.String()))
	cancel()

	// Give goroutines time to clean up
	time.Sleep(500 * time.Millisecond)
	logger.Info("agent stopped")
}

func heartbeatLoop(ctx context.Context, client *comms.GRPCClient, deviceID string, rec *reconciler.Reconciler, logger *zap.Logger) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			hbCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			resp, err := client.Heartbeat(hbCtx, deviceID, agentVersion, nil)
			cancel()

			if err != nil {
				logger.Warn("heartbeat failed", zap.Error(err))
				continue
			}

			if resp.GetManifestUpdated() && resp.GetManifest() != nil {
				logger.Info("received updated manifest via heartbeat")
				rec.SetDesiredState(resp.GetManifest())
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
