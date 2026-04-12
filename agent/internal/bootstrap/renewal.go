package bootstrap

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/edgeguardian/agent/plugins/certificate"
	"go.uber.org/zap"
)

// IdentityCertName is the fixed name used in cert/request and cert/response messages
// for the device's mTLS identity certificate.
const IdentityCertName = "device-identity"

// CertRequester is the subset of MQTT client functionality the renewal loop needs.
type CertRequester interface {
	PublishCertRequest(name, commonName string, sans []string, csrPEM []byte, reqType, currentSerial string) error
}

const (
	defaultMinBetweenAttempts     = 4 * time.Hour
	defaultCriticalWindow         = 24 * time.Hour
	defaultMaxFailuresBeforeFatal = 3
)

// Renewer drives the periodic identity-cert renewal loop.
type Renewer struct {
	mgr          *Manager
	requester    CertRequester
	renewWindow  time.Duration
	tickInterval time.Duration
	logger       *zap.Logger

	minBetweenAttempts     time.Duration
	criticalWindow         time.Duration
	maxFailuresBeforeFatal int

	mu                sync.Mutex
	identity          *Identity
	lastAttempt       time.Time
	consecutiveFailed int
}

// NewRenewer wires a renewer around an already-enrolled identity.
func NewRenewer(mgr *Manager, requester CertRequester, id *Identity,
	renewWindow, tickInterval time.Duration, logger *zap.Logger) *Renewer {

	return &Renewer{
		mgr:                    mgr,
		requester:              requester,
		renewWindow:            renewWindow,
		tickInterval:           tickInterval,
		logger:                 logger,
		minBetweenAttempts:     defaultMinBetweenAttempts,
		criticalWindow:         defaultCriticalWindow,
		maxFailuresBeforeFatal: defaultMaxFailuresBeforeFatal,
		identity:               id,
	}
}

// Run blocks until ctx is cancelled, checking expiry on every tick.
func (r *Renewer) Run(ctx context.Context) {
	r.tryRenew()

	t := time.NewTicker(r.tickInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if ctx.Err() != nil {
				return
			}
			r.tryRenew()
		}
	}
}

func (r *Renewer) tryRenew() {
	r.mu.Lock()
	id := r.identity
	lastAttempt := r.lastAttempt
	failed := r.consecutiveFailed
	r.mu.Unlock()
	if id == nil {
		return
	}
	if !id.ShouldRenew(r.renewWindow) {
		return
	}

	now := time.Now()

	// A RENEWAL response can take minutes under load; skip if still in cooldown.
	if !lastAttempt.IsZero() && now.Sub(lastAttempt) < r.minBetweenAttempts {
		return
	}

	// Escalate to FATAL so the watchdog can restart us into a clean enrollment path.
	timeToExpiry := time.Until(id.NotAfter)
	if timeToExpiry <= r.criticalWindow && failed >= r.maxFailuresBeforeFatal {
		r.logger.Fatal("identity cert renewal failed repeatedly; aborting for watchdog restart",
			zap.String("current_serial", id.SerialHex),
			zap.Duration("time_to_expiry", timeToExpiry),
			zap.Int("consecutive_failures", failed))
	}

	r.logger.Info("identity cert within renewal window, requesting renewal",
		zap.String("current_serial", id.SerialHex),
		zap.Time("not_after", id.NotAfter),
		zap.Int("consecutive_failures", failed))

	r.mu.Lock()
	r.lastAttempt = now
	r.mu.Unlock()

	if err := r.publishRenewal(id); err != nil {
		r.mu.Lock()
		r.consecutiveFailed++
		r.mu.Unlock()
		r.logger.Error("renewal request publish failed", zap.Error(err))
		return
	}
	// Bumped optimistically; reset in HandleRenewalResponse when a response arrives.
	r.mu.Lock()
	r.consecutiveFailed++
	r.mu.Unlock()
}

func (r *Renewer) publishRenewal(id *Identity) error {
	newKey, err := certificate.GenerateKeyPair("ecdsa-p256")
	if err != nil {
		return fmt.Errorf("generate renewal keypair: %w", err)
	}
	csrPEM, err := certificate.CreateCSR(newKey, id.Certificate.Subject.CommonName, nil)
	if err != nil {
		return fmt.Errorf("create renewal CSR: %w", err)
	}

	// Stash the new private key so we can atomically swap when the signed cert arrives.
	keyPEM, err := certificate.MarshalPrivateKey(newKey)
	if err != nil {
		return fmt.Errorf("marshal renewal key: %w", err)
	}
	if err := certificate.WriteAtomic(id.KeyPath+".new", keyPEM, 0600); err != nil {
		return fmt.Errorf("stash renewal key: %w", err)
	}

	if err := r.requester.PublishCertRequest(
		IdentityCertName,
		id.Certificate.Subject.CommonName,
		nil,
		csrPEM,
		"RENEWAL",
		id.SerialHex,
	); err != nil {
		return fmt.Errorf("publish renewal request: %w", err)
	}
	return nil
}

// HandleRenewalResponse swaps in the stashed key + new cert atomically.
func (r *Renewer) HandleRenewalResponse(certPEM []byte) (*Identity, error) {
	r.mu.Lock()
	current := r.identity
	r.mu.Unlock()
	if current == nil {
		return nil, fmt.Errorf("no active identity to renew")
	}

	newKeyPath := current.KeyPath + ".new"
	newKeyPEM, err := readFileIfExists(newKeyPath)
	if err != nil {
		return nil, fmt.Errorf("read stashed renewal key: %w", err)
	}

	if err := r.mgr.Replace(newKeyPEM, certPEM, nil); err != nil {
		return nil, fmt.Errorf("replace identity material: %w", err)
	}
	_ = removeIfExists(newKeyPath)

	updated, err := r.mgr.Load()
	if err != nil {
		return nil, fmt.Errorf("reload identity after renewal: %w", err)
	}
	r.mu.Lock()
	r.identity = updated
	r.consecutiveFailed = 0
	r.lastAttempt = time.Time{}
	r.mu.Unlock()
	r.logger.Info("identity cert renewed",
		zap.String("new_serial", updated.SerialHex),
		zap.Time("new_not_after", updated.NotAfter))
	return updated, nil
}
