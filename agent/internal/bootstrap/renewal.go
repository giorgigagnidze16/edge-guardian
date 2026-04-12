package bootstrap

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/edgeguardian/agent/plugins/certificate"
	"go.uber.org/zap"
)

// CertRequester is the subset of MQTT client functionality the renewal loop needs.
// Kept as an interface so tests can verify the flow without a live broker.
type CertRequester interface {
	PublishCertRequest(name, commonName string, sans []string, csrPEM []byte, reqType, currentSerial string) error
}

// Renewer drives the periodic identity-cert renewal loop.
//
// Contract:
//   - On every tick, compare {NotAfter - now} against renewWindow. If inside the window,
//     publish a RENEWAL cert request with currentSerial pointing at the live cert.
//   - The controller auto-approves renewals and publishes the new cert on cert/response.
//   - A cert-response callback (wired at construction time) decrypts the response,
//     swaps the on-disk material atomically via Manager.Replace, and updates the
//     in-memory identity so subsequent ticks compare against the new NotAfter.
//
// The agent's MQTT TLS config is loaded at connect time, so reloading the cert
// mid-session requires a reconnect to pick up the new key pair — deferred to a
// follow-up (today the reconnect happens on the next restart, which is acceptable
// given the agent runs under watchdog supervision).
type Renewer struct {
	mgr          *Manager
	requester    CertRequester
	renewWindow  time.Duration
	tickInterval time.Duration
	logger       *zap.Logger

	mu       sync.Mutex
	identity *Identity
}

// NewRenewer wires a renewer around an already-enrolled identity. renewWindow is how
// close to expiry we trigger a renewal request (e.g. 14 days). tickInterval is how
// often we check (e.g. 1 hour — renewWindow is long, so this can be lazy).
func NewRenewer(mgr *Manager, requester CertRequester, id *Identity,
	renewWindow, tickInterval time.Duration, logger *zap.Logger) *Renewer {

	return &Renewer{
		mgr:          mgr,
		requester:    requester,
		renewWindow:  renewWindow,
		tickInterval: tickInterval,
		logger:       logger,
		identity:     id,
	}
}

// Run blocks until ctx is cancelled, checking expiry on every tick.
func (r *Renewer) Run(ctx context.Context) {
	// Check immediately on startup so a device that missed its window while powered off
	// renews on the next boot rather than waiting for the first tick.
	r.tryRenew()

	t := time.NewTicker(r.tickInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			r.tryRenew()
		}
	}
}

func (r *Renewer) tryRenew() {
	r.mu.Lock()
	id := r.identity
	r.mu.Unlock()
	if id == nil {
		return
	}
	if !id.ShouldRenew(r.renewWindow) {
		return
	}

	r.logger.Info("identity cert within renewal window, requesting renewal",
		zap.String("current_serial", id.SerialHex),
		zap.Time("not_after", id.NotAfter))

	if err := r.publishRenewal(id); err != nil {
		r.logger.Error("renewal request failed", zap.Error(err))
	}
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

	// Stash the new private key alongside the existing one so we can atomically swap
	// when the signed cert arrives (same pattern the per-app cert plugin uses).
	keyPEM, err := certificate.MarshalPrivateKey(newKey)
	if err != nil {
		return fmt.Errorf("marshal renewal key: %w", err)
	}
	if err := certificate.WriteAtomic(id.KeyPath+".new", keyPEM, 0600); err != nil {
		return fmt.Errorf("stash renewal key: %w", err)
	}

	const identityCertName = "device-identity"
	if err := r.requester.PublishCertRequest(
		identityCertName,
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

// HandleRenewalResponse is called by the cert/response handler when a renewal arrives.
// It swaps in the stashed key + new cert atomically and updates the in-memory identity.
// Returns the new identity for logging/telemetry.
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

	// Swap: replace current key + cert atomically. CA unchanged.
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
	r.mu.Unlock()
	r.logger.Info("identity cert renewed",
		zap.String("new_serial", updated.SerialHex),
		zap.Time("new_not_after", updated.NotAfter))
	return updated, nil
}
