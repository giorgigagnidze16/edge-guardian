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

	// Initialize reconciler with stateless plugins.
	rec := reconciler.New(cfg.ReconcileInterval, logger)
	rec.RegisterPlugin(filemanager.New(logger))
	rec.RegisterPlugin(service.New(logger))

	loadCachedDesiredState(store, rec, logger)

	// --- Identity lifecycle (mTLS bootstrap) -----------------------------------------
	identityMgr, err := bootstrap.NewManager(cfg.DataDir)
	if err != nil {
		logger.Fatal("failed to initialize identity manager", zap.Error(err))
	}

	identity, mqttClient, err := connectWithIdentity(cfg, identityMgr, store, logger)
	if err != nil {
		logger.Fatal("failed to establish MQTT connection", zap.Error(err))
	}
	defer mqttClient.Close()

	// Certificate plugin needs the connected MQTT client as its request transport.
	rec.RegisterPlugin(certificate.New(logger,
		func(name, cn string, sans []string, csrPEM []byte, reqType, serial string) error {
			return mqttClient.PublishCertRequest(name, cn, sans, csrPEM, reqType, serial)
		}, store))

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

	mqttClient.SetDesiredStateHandler(func(manifest *model.DeviceManifest) {
		logger.Info("received desired state update",
			zap.Int64("version", manifest.Version))
		rec.SetDesiredState(manifest)
		if err := store.SaveDesiredState("current", manifest); err != nil {
			logger.Error("failed to save manifest to BoltDB", zap.Error(err))
		}
	})

	// OTA + command dispatch.
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

		// Identity cert renewal loop: check hourly; renew if within 14 days of expiry.
		renewer := bootstrap.NewRenewer(identityMgr, mqttClient, identity,
			14*24*time.Hour, 1*time.Hour, logger)
		go renewer.Run(ctx)
	}

	logger.Info("agent running, waiting for shutdown signal")

	sig := <-sigCh
	logger.Info("received signal, shutting down", zap.String("signal", sig.String()))
	cancel()
	time.Sleep(500 * time.Millisecond)
	logger.Info("agent stopped")
}

// connectWithIdentity implements the two-phase connect:
//
//  1. If a persisted identity already exists on disk and an mTLS broker is configured,
//     skip the bootstrap path entirely and connect directly to the mTLS broker.
//  2. If no identity exists yet, connect to the enrollment broker with username/password,
//     exchange a CSR for a signed leaf cert via the EnrollRequest, persist it, then
//     reconnect to the mTLS broker presenting the new identity.
//  3. If no mTLS broker is configured, stay on the enrollment broker with password auth
//     (legacy path; still functional but not production-recommended).
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

	// Legacy / no-mTLS deployment: stay on password auth.
	if cfg.MQTT.MutualTLSBrokerURL == "" {
		logger.Warn("mtls_broker_url not configured — continuing on password-auth broker " +
			"(not recommended for production)")
		client, err := comms.NewMQTTClient(enrollmentClientConfig(cfg, store), logger)
		if err != nil {
			return nil, nil, err
		}
		if err := client.Connect(10 * time.Second); err != nil {
			logger.Warn("MQTT connection failed (will retry via auto-reconnect)", zap.Error(err))
		}
		if identity == nil {
			if err := enrollWithoutCSR(cfg, client, logger); err != nil {
				client.Close()
				return nil, nil, err
			}
		}
		return identity, client, nil
	}

	// mTLS deployment — fast path: we already have a cert.
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

	// mTLS deployment — first boot: bootstrap via enrollment broker, then switch.
	logger.Info("no persisted identity — running enrollment bootstrap")

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

	// We don't need the bootstrap connection anymore.
	bootstrapClient.Close()

	// Second-phase: connect with mTLS.
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
	}
}

// mqttEnrollAdapter bridges MQTTClient.PublishEnrollRequest to the bootstrap.Enroller interface.
type mqttEnrollAdapter struct {
	client *comms.MQTTClient
}

func (a *mqttEnrollAdapter) Enroll(req *model.EnrollRequest) (*model.RegisterResponse, error) {
	return a.client.PublishEnrollRequest(req)
}

// enrollWithoutCSR is the legacy path for deployments that don't use mTLS. Kept so
// existing installations don't break when upgrading the agent binary.
func enrollWithoutCSR(cfg *config.Config, mqttClient *comms.MQTTClient, logger *zap.Logger) error {
	tokenPath := tokenFilePath(cfg)
	if data, err := os.ReadFile(tokenPath); err == nil && len(data) > 0 {
		logger.Info("loaded device token from file, skipping enrollment",
			zap.String("token_file", tokenPath))
		return nil
	}
	if cfg.Auth.EnrollmentToken == "" {
		return fmt.Errorf("no device token file and no enrollment_token configured")
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
		return fmt.Errorf("enrollment request: %w", err)
	}
	if !resp.Accepted {
		return fmt.Errorf("enrollment rejected by controller: %s", resp.Message)
	}
	if resp.DeviceToken != "" {
		if err := os.WriteFile(tokenPath, []byte(resp.DeviceToken), 0600); err != nil {
			logger.Error("failed to save device token to file", zap.Error(err))
		} else {
			logger.Info("device token saved", zap.String("token_file", tokenPath))
		}
	}
	return nil
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

// checkPostUpdateStatus detects post-update or rollback markers and reports
// the final OTA status back to the controller via MQTT.
func checkPostUpdateStatus(updater *ota.Updater,
	mqttClient *comms.MQTTClient, deviceID string, logger *zap.Logger) {

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

	marker, err := updater.ReadUpdateMarker()
	if err != nil {
		return
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

// tokenFilePath returns the path where the device token is persisted (legacy path).
func tokenFilePath(cfg *config.Config) string {
	if cfg.Auth.TokenFile != "" {
		return cfg.Auth.TokenFile
	}
	return filepath.Join(cfg.DataDir, "device-token")
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
