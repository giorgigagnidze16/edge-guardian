package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"github.com/edgeguardian/agent/internal/bootstrap"
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

	logger := initLogger(cfg)
	defer logger.Sync()

	if err := runPlatform(cfg, logger); err != nil {
		logger.Error("agent terminated with error", zap.Error(err))
		os.Exit(1)
	}
}

// runAgent is the OS-agnostic agent lifecycle. It runs until ctx is
// cancelled - by the interactive signal handler (unix/console) or by the
// Windows Service Control Manager dispatcher.
func runAgent(ctx context.Context, cfg *config.Config, logger *zap.Logger) error {
	logger.Info("EdgeGuardian agent starting",
		zap.String("version", agentVersion),
		zap.String("device_id", cfg.DeviceID),
		zap.String("arch", runtime.GOARCH),
		zap.String("os", runtime.GOOS),
		zap.String("data_dir", cfg.DataDir),
	)

	if err := os.MkdirAll(cfg.DataDir, 0750); err != nil {
		return fmt.Errorf("create data directory: %w", err)
	}

	dbPath := filepath.Join(cfg.DataDir, "agent.db")
	store, err := storage.Open(dbPath)
	if err != nil {
		return fmt.Errorf("open BoltDB at %s: %w", dbPath, err)
	}
	defer func() {
		if err := store.Close(); err != nil {
			logger.Error("failed to close BoltDB", zap.Error(err))
		}
		logger.Info("BoltDB closed")
	}()
	logger.Info("BoltDB opened", zap.String("path", dbPath))

	rec := reconciler.New(cfg.ReconcileInterval, logger)
	rec.RegisterPlugin(filemanager.New(logger))
	rec.RegisterPlugin(service.New(logger))

	loadCachedDesiredState(store, rec, logger)

	identityMgr, err := bootstrap.NewManager(cfg.DataDir)
	if err != nil {
		return fmt.Errorf("initialize identity manager: %w", err)
	}

	identity, mqttClient, err := connectWithIdentityRetry(ctx, cfg, identityMgr, store, logger)
	if err != nil {
		return fmt.Errorf("establish MQTT connection: %w", err)
	}
	defer mqttClient.Close()

	rec.RegisterPlugin(certificate.New(logger,
		func(name, cn string, sans []string, csrPEM []byte, reqType, serial string) error {
			return mqttClient.PublishCertRequest(name, cn, sans, csrPEM, reqType, serial)
		}, store))

	var renewer *bootstrap.Renewer
	if identity != nil {
		renewer = bootstrap.NewRenewer(identityMgr, mqttClient, identity,
			14*24*time.Hour, 1*time.Hour, logger)
	}

	mqttClient.SetCertResponseHandler(func(name string, certPEM, caCertPEM []byte) {
		logger.Info("received signed certificate", zap.String("name", name))

		if name == bootstrap.IdentityCertName && renewer != nil {
			updated, err := renewer.HandleRenewalResponse(certPEM)
			if err != nil {
				logger.Error("identity renewal swap failed", zap.Error(err))
				return
			}
			logger.Info("identity cert renewed",
				zap.String("new_serial", updated.SerialHex),
				zap.Time("new_not_after", updated.NotAfter))
			return
		}

		if err := store.SaveMeta("cert:"+name, string(certPEM)); err != nil {
			logger.Error("failed to save cert to BoltDB", zap.String("name", name), zap.Error(err))
		}
		if len(caCertPEM) > 0 {
			if err := store.SaveMeta("ca_cert", string(caCertPEM)); err != nil {
				logger.Error("failed to save CA cert to BoltDB", zap.Error(err))
			}
		}
	})

	mqttClient.SetDesiredStateHandler(func(manifest *model.DeviceManifest) {
		logger.Info("received desired state update",
			zap.Int64("version", manifest.Version))
		rec.SetDesiredState(manifest)
		if err := store.SaveDesiredState("current", manifest); err != nil {
			logger.Error("failed to save manifest to BoltDB", zap.Error(err))
		}
	})

	updater := ota.NewUpdater(cfg.DataDir, cfg.OTA.SignKey, logger)
	dispatcher := commands.NewDispatcher(updater, mqttClient, cfg.DeviceID, agentVersion, logger)

	mqttClient.SetCommandHandler(func(cmd model.Command) {
		if err := dispatcher.Dispatch(cmd); err != nil {
			logger.Error("command dispatch failed",
				zap.String("id", cmd.ID),
				zap.String("type", cmd.Type),
				zap.Error(err))
		}
	})

	checkPostUpdateStatus(updater, mqttClient, cfg.DeviceID, logger)

	go startHealthServer(cfg.Health.Port, logger)

	healthCollector := health.New(cfg.Health.DiskPath)

	go rec.Run(ctx)

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

	go mqttClient.RunTelemetryLoop(ctx, 30*time.Second, func() *model.DeviceStatus {
		status := healthCollector.Collect()
		status.ReconcileStatus = rec.Status()
		lastRec := rec.LastReconcile()
		if !lastRec.IsZero() {
			status.LastReconcile = lastRec.Format(time.RFC3339)
		}
		return status
	})

	if identity != nil {
		logger.Info("mTLS identity active",
			zap.String("serial", identity.SerialHex),
			zap.Time("not_after", identity.NotAfter),
			zap.String("cert_fingerprint", identity.Fingerprints.CertSHA256),
		)
		if renewer != nil {
			go renewer.Run(ctx)
		}
	}

	logger.Info("agent running, waiting for shutdown signal")

	<-ctx.Done()
	logger.Info("shutdown requested", zap.Error(ctx.Err()))
	time.Sleep(500 * time.Millisecond)
	logger.Info("agent stopped")
	return nil
}

// connectWithIdentityRetry keeps retrying connectWithIdentity with exponential
// backoff until it succeeds or ctx is cancelled. Lets the service stay alive
// through transient broker/controller outages - SCM sees a Running service and
// the agent joins the fleet once the dependency recovers.
func connectWithIdentityRetry(
	ctx context.Context,
	cfg *config.Config,
	mgr *bootstrap.Manager,
	store *storage.Store,
	logger *zap.Logger,
) (*bootstrap.Identity, *comms.MQTTClient, error) {
	const initial = 2 * time.Second
	const max = 60 * time.Second

	backoff := initial
	attempt := 0
	for {
		if err := ctx.Err(); err != nil {
			return nil, nil, err
		}
		attempt++
		identity, client, err := connectWithIdentity(cfg, mgr, store, logger)
		if err == nil {
			if attempt > 1 {
				logger.Info("MQTT connection established after retries",
					zap.Int("attempts", attempt))
			}
			return identity, client, nil
		}
		logger.Warn("MQTT connect failed; will retry",
			zap.Int("attempt", attempt),
			zap.Duration("next_in", backoff),
			zap.Error(err))
		select {
		case <-time.After(backoff):
		case <-ctx.Done():
			return nil, nil, ctx.Err()
		}
		if backoff < max {
			backoff *= 2
			if backoff > max {
				backoff = max
			}
		}
	}
}

// connectWithIdentity loads or enrolls the identity and returns a connected mTLS MQTT client.
func connectWithIdentity(
	cfg *config.Config,
	mgr *bootstrap.Manager,
	store *storage.Store,
	logger *zap.Logger,
) (*bootstrap.Identity, *comms.MQTTClient, error) {

	identity, err := mgr.Load()
	if err != nil {
		return nil, nil, fmt.Errorf("load persisted identity: %w", err)
	}

	if cfg.MQTT.MutualTLSBrokerURL == "" {
		// Password-only auth left revocation unenforceable at the wire level; operators
		// must point the agent at an mTLS broker (ssl://…:8883).
		return nil, nil, fmt.Errorf(
			"mtls_broker_url is required - password-only auth is no longer supported")
	}

	if identity != nil {
		logger.Info("identity cert present, connecting directly to mTLS broker",
			zap.String("serial", identity.SerialHex),
			zap.Time("not_after", identity.NotAfter))
		client, err := connectMtlsWithErr(cfg, identity, store, logger)
		if err != nil {
			return nil, nil, fmt.Errorf("mTLS connect: %w", err)
		}
		return identity, client, nil
	}

	logger.Info("no persisted identity - running enrollment bootstrap")

	bootstrapClient, err := comms.NewMQTTClient(enrollmentClientConfig(cfg, store), logger)
	if err != nil {
		return nil, nil, fmt.Errorf("create bootstrap MQTT client: %w", err)
	}
	if err := bootstrapClient.Connect(30 * time.Second); err != nil {
		bootstrapClient.Close()
		return nil, nil, fmt.Errorf("connect to enrollment broker: %w", err)
	}

	hostname, _ := os.Hostname()
	baseReq := &model.EnrollRequest{
		EnrollmentToken: cfg.Auth.EnrollmentToken,
		DeviceID:        cfg.DeviceID,
		Hostname:        hostname,
		Architecture:    runtime.GOARCH,
		OS:              runtime.GOOS,
		AgentVersion:    agentVersion,
		Labels:          cfg.Labels,
	}

	adapter := &mqttEnrollAdapter{client: bootstrapClient}
	identity, err = mgr.Enroll(adapter, baseReq, cfg.DeviceID, nil)
	if err != nil {
		bootstrapClient.Close()
		return nil, nil, fmt.Errorf("identity enrollment: %w", err)
	}
	logger.Info("identity enrollment succeeded",
		zap.String("serial", identity.SerialHex),
		zap.Time("not_after", identity.NotAfter))

	bootstrapClient.Close()

	mtlsClient, err := connectMtlsWithErr(cfg, identity, store, logger)
	if err != nil {
		return nil, nil, fmt.Errorf("mTLS reconnect after enrollment: %w", err)
	}
	return identity, mtlsClient, nil
}

func connectMtlsWithErr(cfg *config.Config, id *bootstrap.Identity, store *storage.Store, logger *zap.Logger) (*comms.MQTTClient, error) {
	client, err := comms.NewMQTTClient(comms.MQTTConfig{
		BrokerURL: cfg.MQTT.MutualTLSBrokerURL,
		DeviceID:  cfg.DeviceID,
		TopicRoot: cfg.MQTT.TopicRoot,
		Store:     store,
		TLS: comms.TLSConfig{
			CACertPath:         id.CaPath,
			IdentityCertPath:   id.CertPath,
			IdentityKeyPath:    id.KeyPath,
			ServerName:         cfg.MQTT.TLSServerName,
			InsecureSkipVerify: cfg.MQTT.InsecureSkipVerify,
		},
	}, logger)
	if err != nil {
		return nil, err
	}
	if err := client.Connect(30 * time.Second); err != nil {
		client.Close()
		return nil, err
	}
	return client, nil
}

func enrollmentClientConfig(cfg *config.Config, store *storage.Store) comms.MQTTConfig {
	return comms.MQTTConfig{
		BrokerURL: cfg.MQTT.BrokerURL,
		DeviceID:  cfg.DeviceID,
		Username:  cfg.MQTT.Username,
		Password:  cfg.MQTT.Password,
		TopicRoot: cfg.MQTT.TopicRoot,
		Store:     store,
		Bootstrap: true,
	}
}

type mqttEnrollAdapter struct {
	client *comms.MQTTClient
}

func (a *mqttEnrollAdapter) Enroll(req *model.EnrollRequest) (*model.RegisterResponse, error) {
	return a.client.PublishEnrollRequest(req)
}

func loadCachedDesiredState(store *storage.Store, rec *reconciler.Reconciler, logger *zap.Logger) {
	var cached model.DeviceManifest
	found, err := store.LoadDesiredState("current", &cached)
	if err != nil {
		logger.Warn("failed to load cached desired state", zap.Error(err))
		return
	}
	if found {
		logger.Info("loaded cached desired state from BoltDB",
			zap.Int64("version", cached.Version))
		rec.SetDesiredState(&cached)
	}
}

// checkPostUpdateStatus reports OTA outcome when an update or rollback marker is found.
func checkPostUpdateStatus(updater *ota.Updater,
	mqttClient *comms.MQTTClient, deviceID string, logger *zap.Logger) {

	rollbackExists, _ := updater.ReadRollbackMarker()
	if rollbackExists {
		logger.Warn("rollback marker detected - previous OTA update was rolled back")
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

	marker, err := updater.ReadUpdateMarker()
	if err != nil {
		return
	}
	logger.Info("update marker detected - OTA update completed successfully",
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

// initLogger builds a zap logger that writes JSON to stdout AND to
// {DataDir}/logs/agent.log. File output is critical under Windows SCM where
// stdout is discarded - the log file gives operators post-mortem visibility.
func initLogger(cfg *config.Config) *zap.Logger {
	var zapLevel zapcore.Level
	switch cfg.LogLevel {
	case "debug":
		zapLevel = zapcore.DebugLevel
	case "warn":
		zapLevel = zapcore.WarnLevel
	case "error":
		zapLevel = zapcore.ErrorLevel
	default:
		zapLevel = zapcore.InfoLevel
	}

	outputs := []string{"stdout"}
	if cfg.DataDir != "" {
		logDir := filepath.Join(cfg.DataDir, "logs")
		if err := os.MkdirAll(logDir, 0750); err == nil {
			// zap requires forward slashes in paths on all platforms.
			logFile := filepath.ToSlash(filepath.Join(logDir, "agent.log"))
			outputs = append(outputs, logFile)
		}
	}

	zapCfg := zap.Config{
		Level:            zap.NewAtomicLevelAt(zapLevel),
		Development:      false,
		Encoding:         "json",
		EncoderConfig:    zap.NewProductionEncoderConfig(),
		OutputPaths:      outputs,
		ErrorOutputPaths: []string{"stderr"},
	}

	logger, err := zapCfg.Build()
	if err != nil {
		panic(fmt.Sprintf("failed to initialize logger: %v", err))
	}

	return logger
}
