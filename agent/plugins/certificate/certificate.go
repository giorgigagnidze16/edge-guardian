package certificate

import (
	"context"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"

	"github.com/edgeguardian/agent/internal/reconciler"
	"github.com/edgeguardian/agent/internal/storage"
	"go.uber.org/zap"
)

const defaultRenewBeforeDays = 30

// CertRequester sends a CSR to the controller via MQTT.
type CertRequester func(name, commonName string, sans []string, csrPEM []byte, reqType, currentSerial string) error

// CertificateManager reconciles X.509 certificates declared in the device manifest.
type CertificateManager struct {
	logger    *zap.Logger
	requester CertRequester
	store     *storage.Store
}

// New creates a CertificateManager plugin.
func New(logger *zap.Logger, requester CertRequester, store *storage.Store) *CertificateManager {
	return &CertificateManager{
		logger:    logger,
		requester: requester,
		store:     store,
	}
}

func (cm *CertificateManager) Name() string        { return "certificate_manager" }
func (cm *CertificateManager) CanHandle(kind string) bool { return kind == "certificate" }

func (cm *CertificateManager) Reconcile(ctx context.Context, specs []reconciler.ResourceSpec) []reconciler.Action {
	var actions []reconciler.Action

	for _, spec := range specs {
		select {
		case <-ctx.Done():
			actions = append(actions, reconciler.Action{
				Plugin: cm.Name(), Resource: spec.Name,
				ActionType: reconciler.ActionSkipped, Description: "context cancelled",
			})
			return actions
		default:
		}

		action := cm.reconcileCert(spec)
		actions = append(actions, action)
	}

	return actions
}

func (cm *CertificateManager) reconcileCert(spec reconciler.ResourceSpec) reconciler.Action {
	name, _ := spec.Fields["name"].(string)
	cn, _ := spec.Fields["commonName"].(string)
	certPath, _ := spec.Fields["certPath"].(string)
	keyPath, _ := spec.Fields["keyPath"].(string)
	keyAlgo, _ := spec.Fields["keyAlgo"].(string)

	// Extract SANs (stored as []interface{} from JSON)
	var sans []string
	if rawSans, ok := spec.Fields["sans"].([]interface{}); ok {
		for _, s := range rawSans {
			if str, ok := s.(string); ok {
				sans = append(sans, str)
			}
		}
	}
	if strSans, ok := spec.Fields["sans"].([]string); ok {
		sans = strSans
	}

	// 1. Check for pending signed cert in BoltDB (delivered via MQTT)
	if pending, _ := cm.store.GetMeta("cert:" + name); pending != "" {
		if err := cm.installCert(name, certPath, keyPath, []byte(pending)); err != nil {
			return reconciler.Action{
				Plugin: cm.Name(), Resource: name,
				ActionType: reconciler.ActionCreate,
				Error:       fmt.Sprintf("failed to install cert: %v", err),
			}
		}
		cm.store.SaveMeta("cert:"+name, "")
		return reconciler.Action{
			Plugin: cm.Name(), Resource: name,
			ActionType: reconciler.ActionCreate, Description: "certificate installed from controller",
		}
	}

	// 2. Ensure private key exists
	if _, err := os.Stat(keyPath); os.IsNotExist(err) {
		if err := cm.ensureDir(keyPath); err != nil {
			return cm.errorAction(name, "create key dir", err)
		}
		key, err := GenerateKeyPair(keyAlgo)
		if err != nil {
			return cm.errorAction(name, "generate key", err)
		}
		keyPEM, err := MarshalPrivateKey(key)
		if err != nil {
			return cm.errorAction(name, "marshal key", err)
		}
		if err := WriteAtomic(keyPath, keyPEM, 0600); err != nil {
			return cm.errorAction(name, "write key", err)
		}
		cm.logger.Info("generated private key", zap.String("cert", name), zap.String("path", keyPath))
	}

	// 3. Check certificate state
	if _, err := os.Stat(certPath); os.IsNotExist(err) {
		// No cert — request one
		return cm.requestCert(name, cn, sans, keyPath, "manifest", "")
	}

	// Cert exists — check validity
	certPEM, err := os.ReadFile(certPath)
	if err != nil {
		return cm.errorAction(name, "read cert", err)
	}

	cert, err := ParseCertificatePEM(certPEM)
	if err != nil {
		return cm.errorAction(name, "parse cert", err)
	}

	now := time.Now()
	if now.After(cert.NotAfter) {
		// Expired — request initial (not renewal, since old cert is dead)
		cm.logger.Warn("certificate expired", zap.String("cert", name), zap.Time("expired", cert.NotAfter))
		return cm.requestCert(name, cn, sans, keyPath, "initial", "")
	}

	renewAt := cert.NotAfter.AddDate(0, 0, -defaultRenewBeforeDays)
	if now.After(renewAt) {
		// In renewal window — generate new key and request renewal
		cm.logger.Info("certificate entering renewal window",
			zap.String("cert", name), zap.Time("expires", cert.NotAfter))
		return cm.requestRenewal(name, cn, sans, keyPath, keyAlgo, cert.SerialNumber)
	}

	return reconciler.Action{
		Plugin: cm.Name(), Resource: name,
		ActionType: reconciler.ActionNoop, Description: "certificate valid",
	}
}

func (cm *CertificateManager) requestCert(name, cn string, sans []string, keyPath, reqType, serial string) reconciler.Action {
	key, err := LoadPrivateKey(keyPath)
	if err != nil {
		return cm.errorAction(name, "load key for CSR", err)
	}

	csrPEM, err := CreateCSR(key, cn, sans)
	if err != nil {
		return cm.errorAction(name, "create CSR", err)
	}

	if err := cm.requester(name, cn, sans, csrPEM, reqType, serial); err != nil {
		return cm.errorAction(name, "send cert request", err)
	}

	cm.logger.Info("certificate requested",
		zap.String("cert", name), zap.String("type", reqType))

	return reconciler.Action{
		Plugin: cm.Name(), Resource: name,
		ActionType: reconciler.ActionCreate,
		Description: fmt.Sprintf("CSR sent to controller (type=%s)", reqType),
	}
}

func (cm *CertificateManager) requestRenewal(name, cn string, sans []string, keyPath, keyAlgo string, serial *big.Int) reconciler.Action {
	// Generate NEW key for renewal
	newKey, err := GenerateKeyPair(keyAlgo)
	if err != nil {
		return cm.errorAction(name, "generate renewal key", err)
	}
	newKeyPEM, err := MarshalPrivateKey(newKey)
	if err != nil {
		return cm.errorAction(name, "marshal renewal key", err)
	}
	// Write to .new file; will be swapped when cert arrives
	if err := WriteAtomic(keyPath+".new", newKeyPEM, 0600); err != nil {
		return cm.errorAction(name, "write renewal key", err)
	}

	csrPEM, err := CreateCSR(newKey, cn, sans)
	if err != nil {
		return cm.errorAction(name, "create renewal CSR", err)
	}

	serialHex := serial.Text(16)
	if err := cm.requester(name, cn, sans, csrPEM, "renewal", serialHex); err != nil {
		return cm.errorAction(name, "send renewal request", err)
	}

	cm.logger.Info("certificate renewal requested",
		zap.String("cert", name), zap.String("oldSerial", serialHex))

	return reconciler.Action{
		Plugin: cm.Name(), Resource: name,
		ActionType: reconciler.ActionUpdate,
		Description: fmt.Sprintf("renewal CSR sent (oldSerial=%s)", serialHex),
	}
}

func (cm *CertificateManager) installCert(name, certPath, keyPath string, certPEM []byte) error {
	if err := cm.ensureDir(certPath); err != nil {
		return err
	}

	// If a .new key exists (from renewal), swap it in
	newKeyPath := keyPath + ".new"
	if _, err := os.Stat(newKeyPath); err == nil {
		if err := os.Rename(newKeyPath, keyPath); err != nil {
			return fmt.Errorf("swap renewal key: %w", err)
		}
		cm.logger.Info("swapped renewal key", zap.String("cert", name))
	}

	if err := WriteAtomic(certPath, certPEM, 0644); err != nil {
		return fmt.Errorf("write cert: %w", err)
	}

	cm.logger.Info("certificate installed",
		zap.String("cert", name), zap.String("path", certPath))
	return nil
}

func (cm *CertificateManager) ensureDir(filePath string) error {
	return os.MkdirAll(filepath.Dir(filePath), 0755)
}

func (cm *CertificateManager) errorAction(name, context string, err error) reconciler.Action {
	cm.logger.Error("certificate reconciliation error",
		zap.String("cert", name), zap.String("context", context), zap.Error(err))
	return reconciler.Action{
		Plugin: cm.Name(), Resource: name,
		ActionType: reconciler.ActionSkipped,
		Error:       fmt.Sprintf("%s: %v", context, err),
	}
}
