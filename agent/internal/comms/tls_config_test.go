package comms

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestTLSConfig_IsZero(t *testing.T) {
	if !(TLSConfig{}).IsZero() {
		t.Fatal("zero-value TLSConfig should report IsZero")
	}
	c := TLSConfig{CACertPath: "/tmp/ca.pem"}
	if c.IsZero() {
		t.Fatal("TLSConfig with CA path should NOT report IsZero")
	}
}

func TestTLSConfig_Build_RequiresCA(t *testing.T) {
	_, err := TLSConfig{}.Build()
	if err == nil {
		t.Fatal("Build without CA should error")
	}
}

func TestTLSConfig_Build_CAOnly(t *testing.T) {
	dir := t.TempDir()
	caPath := filepath.Join(dir, "ca.pem")
	writeSelfSignedCert(t, caPath)

	cfg, err := TLSConfig{CACertPath: caPath}.Build()
	if err != nil {
		t.Fatalf("Build CA-only: %v", err)
	}
	if cfg.RootCAs == nil {
		t.Fatal("RootCAs should be populated")
	}
	if cfg.MinVersion != 0x0303 { // tls.VersionTLS12
		t.Fatalf("expected TLS 1.2 minimum, got 0x%x", cfg.MinVersion)
	}
	if len(cfg.Certificates) != 0 {
		t.Fatal("no client cert expected when identity paths empty")
	}
}

func TestTLSConfig_Build_FullMutualTLS(t *testing.T) {
	dir := t.TempDir()
	caPath := filepath.Join(dir, "ca.pem")
	certPath := filepath.Join(dir, "identity.crt")
	keyPath := filepath.Join(dir, "identity.key")
	writeSelfSignedCert(t, caPath)
	writeSelfSignedKeypair(t, certPath, keyPath)

	cfg, err := TLSConfig{
		CACertPath:       caPath,
		IdentityCertPath: certPath,
		IdentityKeyPath:  keyPath,
		ServerName:       "emqx.example",
	}.Build()
	if err != nil {
		t.Fatalf("Build mTLS: %v", err)
	}
	if len(cfg.Certificates) != 1 {
		t.Fatalf("expected one client cert, got %d", len(cfg.Certificates))
	}
	if cfg.ServerName != "emqx.example" {
		t.Fatalf("server name should be passed through: %q", cfg.ServerName)
	}
}

func TestTLSConfig_Build_PartialIdentityRejected(t *testing.T) {
	dir := t.TempDir()
	caPath := filepath.Join(dir, "ca.pem")
	writeSelfSignedCert(t, caPath)
	certOnly := filepath.Join(dir, "cert.pem")
	writeSelfSignedCert(t, certOnly)

	_, err := TLSConfig{
		CACertPath:       caPath,
		IdentityCertPath: certOnly,
		// IdentityKeyPath intentionally omitted
	}.Build()
	if err == nil {
		t.Fatal("partial identity (cert w/o key) should be rejected")
	}
}

func TestTLSConfig_Build_InvalidCA(t *testing.T) {
	dir := t.TempDir()
	caPath := filepath.Join(dir, "ca.pem")
	_ = os.WriteFile(caPath, []byte("not a pem file"), 0644)

	_, err := TLSConfig{CACertPath: caPath}.Build()
	if err == nil {
		t.Fatal("unparseable CA should be rejected")
	}
}

// --- helpers -----------------------------------------------------------------

func writeSelfSignedCert(t *testing.T, path string) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create cert: %v", err)
	}
	out := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	if err := os.WriteFile(path, out, 0644); err != nil {
		t.Fatalf("write cert: %v", err)
	}
}

func writeSelfSignedKeypair(t *testing.T, certPath, keyPath string) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "device"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create leaf: %v", err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyDER, _ := x509.MarshalPKCS8PrivateKey(key)
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	if err := os.WriteFile(certPath, certPEM, 0644); err != nil {
		t.Fatalf("write cert: %v", err)
	}
	if err := os.WriteFile(keyPath, keyPEM, 0600); err != nil {
		t.Fatalf("write key: %v", err)
	}
}
