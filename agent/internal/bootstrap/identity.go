// Package bootstrap owns the agent's mTLS identity lifecycle:
//   - one-time enrollment (exchange an enrollment token for a signed leaf cert)
//   - on-disk persistence of the cert, private key, and trust anchor
//   - loading persisted material on subsequent starts so enrollment only runs once
//   - expiry inspection for the renewal path
//
// It is deliberately decoupled from the MQTT client — the caller passes in a small
// Enroller interface so the same logic works over any request/response transport.
package bootstrap

import (
	"crypto/x509"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/edgeguardian/agent/internal/model"
	"github.com/edgeguardian/agent/plugins/certificate"
)

const (
	// Subdirectory under DataDir where identity material is stored.
	identityDir = "identity"

	identityKeyFile  = "identity.key"
	identityCertFile = "identity.crt"
	caCertFile       = "ca.crt"

	// ownerRWOnly (0600) for private key; others are world-readable but owner-writable.
	ownerRWOnly        = 0600
	ownerRWOthersRead  = 0644
	ownerRWXOthersRead = 0755
)

// Identity is the resolved on-disk identity for this agent.
type Identity struct {
	DataDir      string
	KeyPath      string
	CertPath     string
	CaPath       string
	Certificate  *x509.Certificate // parsed leaf
	SerialHex    string            // cert.SerialNumber in lowercase hex, used for RENEWAL requests
	NotAfter     time.Time
	Fingerprints Fingerprints
}

// Fingerprints of the on-disk cert files — exposed for logs/telemetry.
type Fingerprints struct {
	CertSHA256 string
	CaSHA256   string
}

// Enroller abstracts the transport used to exchange an EnrollRequest for a RegisterResponse.
// Today this is an MQTT client, but tests and future transports can implement it independently.
type Enroller interface {
	Enroll(req *model.EnrollRequest) (*model.RegisterResponse, error)
}

// Manager owns the on-disk identity material and enrollment flow.
type Manager struct {
	dataDir string
}

// NewManager constructs an identity manager rooted at the given DataDir. The
// {DataDir}/identity/ directory is created if missing.
func NewManager(dataDir string) (*Manager, error) {
	dir := filepath.Join(dataDir, identityDir)
	if err := os.MkdirAll(dir, ownerRWXOthersRead); err != nil {
		return nil, fmt.Errorf("create identity dir %s: %w", dir, err)
	}
	return &Manager{dataDir: dir}, nil
}

// Load reads the persisted identity. Returns (nil, nil) if no identity exists yet
// (the caller should then run Enroll). Any parsing/IO failure that indicates
// corruption is returned as an error so the caller doesn't silently re-enroll and
// mask a misconfigured device.
func (m *Manager) Load() (*Identity, error) {
	keyPath := m.keyPath()
	certPath := m.certPath()
	caPath := m.caPath()

	if _, err := os.Stat(certPath); os.IsNotExist(err) {
		return nil, nil
	} else if err != nil {
		return nil, fmt.Errorf("stat %s: %w", certPath, err)
	}

	certPEM, err := os.ReadFile(certPath)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", certPath, err)
	}
	cert, err := certificate.ParseCertificatePEM(certPEM)
	if err != nil {
		return nil, fmt.Errorf("parse %s: %w", certPath, err)
	}
	if _, err := os.Stat(keyPath); err != nil {
		return nil, fmt.Errorf("identity cert present but private key missing at %s: %w", keyPath, err)
	}
	if _, err := os.Stat(caPath); err != nil {
		return nil, fmt.Errorf("identity cert present but CA cert missing at %s: %w", caPath, err)
	}
	caPEM, err := os.ReadFile(caPath)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", caPath, err)
	}

	return &Identity{
		DataDir:     m.dataDir,
		KeyPath:     keyPath,
		CertPath:    certPath,
		CaPath:      caPath,
		Certificate: cert,
		SerialHex:   cert.SerialNumber.Text(16),
		NotAfter:    cert.NotAfter,
		Fingerprints: Fingerprints{
			CertSHA256: certificate.Fingerprint(certPEM),
			CaSHA256:   certificate.Fingerprint(caPEM),
		},
	}, nil
}

// Enroll runs the first-time bootstrap: generate a local keypair + CSR, send the
// EnrollRequest via the transport, and persist the returned cert/key/CA atomically.
//
// baseReq is the caller-populated enrollment payload (device id, hostname, etc);
// the CsrPem/CommonName/Sans fields are filled in here.
func (m *Manager) Enroll(enroller Enroller, baseReq *model.EnrollRequest, commonName string, sans []string) (*Identity, error) {
	if baseReq.EnrollmentToken == "" {
		return nil, fmt.Errorf("enrollment token required for first-time bootstrap")
	}

	key, err := certificate.GenerateKeyPair("ecdsa-p256")
	if err != nil {
		return nil, fmt.Errorf("generate keypair: %w", err)
	}
	csrPEM, err := certificate.CreateCSR(key, commonName, sans)
	if err != nil {
		return nil, fmt.Errorf("create CSR: %w", err)
	}

	req := *baseReq // copy so we don't mutate caller's struct
	req.CsrPem = string(csrPEM)
	req.CommonName = commonName
	req.Sans = sans

	resp, err := enroller.Enroll(&req)
	if err != nil {
		return nil, fmt.Errorf("enrollment request: %w", err)
	}
	if !resp.Accepted {
		return nil, fmt.Errorf("enrollment rejected: %s", resp.Message)
	}
	if resp.IdentityCertPem == "" {
		return nil, fmt.Errorf("enrollment accepted but server returned no identity cert")
	}
	if resp.CaCertPem == "" {
		return nil, fmt.Errorf("enrollment accepted but server returned no CA cert")
	}

	keyPEM, err := certificate.MarshalPrivateKey(key)
	if err != nil {
		return nil, fmt.Errorf("marshal private key: %w", err)
	}
	if err := m.persist(keyPEM, []byte(resp.IdentityCertPem), []byte(resp.CaCertPem)); err != nil {
		return nil, err
	}
	return m.Load()
}

// Replace atomically swaps the cert + key (and optionally CA) on disk. Used by the
// renewal path once the controller returns a new signed cert.
func (m *Manager) Replace(keyPEM, certPEM, caPEM []byte) error {
	return m.persist(keyPEM, certPEM, caPEM)
}

func (m *Manager) persist(keyPEM, certPEM, caPEM []byte) error {
	// Write key first with 0600 so nothing observable could leak it even briefly.
	if err := certificate.WriteAtomic(m.keyPath(), keyPEM, ownerRWOnly); err != nil {
		return fmt.Errorf("persist key: %w", err)
	}
	if err := certificate.WriteAtomic(m.certPath(), certPEM, ownerRWOthersRead); err != nil {
		return fmt.Errorf("persist cert: %w", err)
	}
	if len(caPEM) > 0 {
		if err := certificate.WriteAtomic(m.caPath(), caPEM, ownerRWOthersRead); err != nil {
			return fmt.Errorf("persist ca: %w", err)
		}
	}
	return nil
}

// Paths returns the on-disk locations for cert/key/CA. Handy when wiring the MQTT TLS config.
func (m *Manager) Paths() (certPath, keyPath, caPath string) {
	return m.certPath(), m.keyPath(), m.caPath()
}

func (m *Manager) keyPath() string  { return filepath.Join(m.dataDir, identityKeyFile) }
func (m *Manager) certPath() string { return filepath.Join(m.dataDir, identityCertFile) }
func (m *Manager) caPath() string   { return filepath.Join(m.dataDir, caCertFile) }

// ShouldRenew returns true when the cert is within renewWindow of expiry.
func (id *Identity) ShouldRenew(renewWindow time.Duration) bool {
	if id == nil || id.Certificate == nil {
		return false
	}
	return time.Until(id.NotAfter) <= renewWindow
}
