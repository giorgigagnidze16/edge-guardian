package certificate

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"context"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/edgeguardian/agent/internal/reconciler"
	"github.com/edgeguardian/agent/internal/storage"
	"go.uber.org/zap"
)

func testLogger() *zap.Logger {
	l, _ := zap.NewDevelopment()
	return l
}

func tempStore(t *testing.T) *storage.Store {
	t.Helper()
	dir := t.TempDir()
	store, err := storage.Open(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { store.Close() })
	return store
}

type mockRequester struct {
	mu    sync.Mutex
	calls []certRequest
}

type certRequest struct {
	name, cn, reqType, serial string
	sans                      []string
}

func (m *mockRequester) request(name, cn string, sans []string, csrPEM []byte, reqType, serial string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.calls = append(m.calls, certRequest{name: name, cn: cn, reqType: reqType, serial: serial, sans: sans})
	return nil
}

func (m *mockRequester) callCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.calls)
}

func (m *mockRequester) lastCall() certRequest {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.calls[len(m.calls)-1]
}

func certSpec(name, cn, certPath, keyPath string) reconciler.ResourceSpec {
	return reconciler.ResourceSpec{
		Kind: "certificate",
		Name: name,
		Fields: map[string]interface{}{
			"name":       name,
			"commonName": cn,
			"sans":       []string{},
			"certPath":   certPath,
			"keyPath":    keyPath,
			"keyAlgo":    "",
		},
	}
}

func TestCertificateManager_NameAndCanHandle(t *testing.T) {
	store := tempStore(t)
	m := &mockRequester{}
	cm := New(testLogger(), m.request, store)

	if cm.Name() != "certificate_manager" {
		t.Fatalf("expected 'certificate_manager', got %q", cm.Name())
	}
	if !cm.CanHandle("certificate") {
		t.Fatal("expected CanHandle('certificate') to be true")
	}
	if cm.CanHandle("file") {
		t.Fatal("expected CanHandle('file') to be false")
	}
}

func TestCertificateManager_CreateKeyAndRequestCert(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "tls", "cert.pem")
	keyPath := filepath.Join(dir, "tls", "key.pem")

	store := tempStore(t)
	m := &mockRequester{}
	cm := New(testLogger(), m.request, store)

	specs := []reconciler.ResourceSpec{certSpec("test-cert", "device.local", certPath, keyPath)}
	actions := cm.Reconcile(context.Background(), specs)

	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionCreate {
		t.Fatalf("expected ActionCreate, got %s", actions[0].ActionType)
	}
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	// Key should have been generated
	if _, err := os.Stat(keyPath); err != nil {
		t.Fatalf("key should exist: %v", err)
	}

	// CSR should have been sent to requester
	if m.callCount() != 1 {
		t.Fatalf("expected 1 request, got %d", m.callCount())
	}
	call := m.lastCall()
	if call.name != "test-cert" || call.cn != "device.local" || call.reqType != "manifest" {
		t.Fatalf("unexpected request: %+v", call)
	}
}

func TestCertificateManager_InstallFromBoltDB(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "cert.pem")
	keyPath := filepath.Join(dir, "key.pem")

	// Pre-create key so the plugin doesn't try to generate one
	key, _ := GenerateKeyPair("")
	keyPEM, _ := MarshalPrivateKey(key)
	os.MkdirAll(filepath.Dir(keyPath), 0755)
	os.WriteFile(keyPath, keyPEM, 0600)

	// Simulate a signed cert in BoltDB (from MQTT response)
	store := tempStore(t)
	fakeCert := "-----BEGIN CERTIFICATE-----\nfake\n-----END CERTIFICATE-----\n"
	store.SaveMeta("cert:test-cert", fakeCert)

	m := &mockRequester{}
	cm := New(testLogger(), m.request, store)

	specs := []reconciler.ResourceSpec{certSpec("test-cert", "device.local", certPath, keyPath)}
	actions := cm.Reconcile(context.Background(), specs)

	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionCreate {
		t.Fatalf("expected ActionCreate, got %s", actions[0].ActionType)
	}
	if actions[0].Description != "certificate installed from controller" {
		t.Fatalf("unexpected description: %s", actions[0].Description)
	}

	// Cert should be written to disk
	data, err := os.ReadFile(certPath)
	if err != nil {
		t.Fatalf("cert should exist: %v", err)
	}
	if string(data) != fakeCert {
		t.Fatalf("cert content mismatch")
	}

	// BoltDB entry should be cleared
	val, _ := store.GetMeta("cert:test-cert")
	if val != "" {
		t.Fatal("BoltDB cert entry should be cleared after install")
	}

	// No MQTT request should have been sent
	if m.callCount() != 0 {
		t.Fatal("no cert request should be sent when installing from BoltDB")
	}
}

func TestCertificateManager_ValidCertNoop(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "cert.pem")
	keyPath := filepath.Join(dir, "key.pem")

	// Generate key and self-signed cert valid for 365 days
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	keyPEM, _ := MarshalPrivateKey(key)
	os.WriteFile(keyPath, keyPEM, 0600)

	certPEM := selfSignedCert(t, key, "device.local", 365)
	os.WriteFile(certPath, certPEM, 0644)

	store := tempStore(t)
	m := &mockRequester{}
	cm := New(testLogger(), m.request, store)

	specs := []reconciler.ResourceSpec{certSpec("test-cert", "device.local", certPath, keyPath)}
	actions := cm.Reconcile(context.Background(), specs)

	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionNoop {
		t.Fatalf("expected ActionNoop, got %s: %s", actions[0].ActionType, actions[0].Description)
	}
	if m.callCount() != 0 {
		t.Fatal("no request should be sent for valid cert")
	}
}

func TestCertificateManager_ExpiredCertRequestsInitial(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "cert.pem")
	keyPath := filepath.Join(dir, "key.pem")

	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	keyPEM, _ := MarshalPrivateKey(key)
	os.WriteFile(keyPath, keyPEM, 0600)

	// Cert expired 1 day ago
	certPEM := selfSignedCertExpired(t, key, "device.local")
	os.WriteFile(certPath, certPEM, 0644)

	store := tempStore(t)
	m := &mockRequester{}
	cm := New(testLogger(), m.request, store)

	specs := []reconciler.ResourceSpec{certSpec("test-cert", "device.local", certPath, keyPath)}
	actions := cm.Reconcile(context.Background(), specs)

	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionCreate {
		t.Fatalf("expected ActionCreate for expired cert, got %s", actions[0].ActionType)
	}
	if m.callCount() != 1 {
		t.Fatal("expected cert request for expired cert")
	}
	if m.lastCall().reqType != "initial" {
		t.Fatalf("expected initial request type for expired cert, got %q", m.lastCall().reqType)
	}
}

func TestCertificateManager_ExpiringCertRequestsRenewal(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "cert.pem")
	keyPath := filepath.Join(dir, "key.pem")

	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	keyPEM, _ := MarshalPrivateKey(key)
	os.WriteFile(keyPath, keyPEM, 0600)

	// Cert expires in 15 days (within 30-day renewal window)
	certPEM := selfSignedCert(t, key, "device.local", 15)
	os.WriteFile(certPath, certPEM, 0644)

	store := tempStore(t)
	m := &mockRequester{}
	cm := New(testLogger(), m.request, store)

	specs := []reconciler.ResourceSpec{certSpec("test-cert", "device.local", certPath, keyPath)}
	actions := cm.Reconcile(context.Background(), specs)

	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("expected ActionUpdate for renewal, got %s", actions[0].ActionType)
	}
	if m.callCount() != 1 {
		t.Fatal("expected renewal request")
	}
	if m.lastCall().reqType != "renewal" {
		t.Fatalf("expected renewal type, got %q", m.lastCall().reqType)
	}
	if m.lastCall().serial == "" {
		t.Fatal("renewal request should include current serial")
	}

	// A .new key should have been generated for the renewal
	if _, err := os.Stat(keyPath + ".new"); err != nil {
		t.Fatalf("renewal key (.new) should exist: %v", err)
	}
}

func TestCertificateManager_RenewalKeySwap(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "cert.pem")
	keyPath := filepath.Join(dir, "key.pem")

	// Original key
	origKey, _ := GenerateKeyPair("")
	origKeyPEM, _ := MarshalPrivateKey(origKey)
	os.WriteFile(keyPath, origKeyPEM, 0600)

	// New key (simulates renewal key)
	newKey, _ := GenerateKeyPair("")
	newKeyPEM, _ := MarshalPrivateKey(newKey)
	os.WriteFile(keyPath+".new", newKeyPEM, 0600)

	// Signed cert waiting in BoltDB
	store := tempStore(t)
	store.SaveMeta("cert:test-cert", "-----BEGIN CERTIFICATE-----\nrenewed\n-----END CERTIFICATE-----\n")

	m := &mockRequester{}
	cm := New(testLogger(), m.request, store)

	specs := []reconciler.ResourceSpec{certSpec("test-cert", "device.local", certPath, keyPath)}
	actions := cm.Reconcile(context.Background(), specs)

	if actions[0].ActionType != reconciler.ActionCreate {
		t.Fatalf("expected ActionCreate, got %s", actions[0].ActionType)
	}

	// .new key should have been swapped to the main key path
	if _, err := os.Stat(keyPath + ".new"); !os.IsNotExist(err) {
		t.Fatal(".new key should have been renamed")
	}

	// Main key should now be the new key
	loadedKey, _ := os.ReadFile(keyPath)
	if string(loadedKey) != string(newKeyPEM) {
		t.Fatal("key should have been swapped to the new key")
	}
}

func TestCertificateManager_ContextCancelled(t *testing.T) {
	store := tempStore(t)
	m := &mockRequester{}
	cm := New(testLogger(), m.request, store)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	specs := []reconciler.ResourceSpec{certSpec("a", "a.local", "/tmp/a.crt", "/tmp/a.key")}
	actions := cm.Reconcile(ctx, specs)

	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionSkipped {
		t.Fatalf("expected ActionSkipped, got %s", actions[0].ActionType)
	}
}

// --- Test helpers ---

func selfSignedCert(t *testing.T, key *ecdsa.PrivateKey, cn string, validDays int) []byte {
	t.Helper()
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: cn},
		NotBefore:    time.Now().Add(-1 * time.Hour),
		NotAfter:     time.Now().AddDate(0, 0, validDays),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create cert: %v", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})
}

func selfSignedCertExpired(t *testing.T, key *ecdsa.PrivateKey, cn string) []byte {
	t.Helper()
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: cn},
		NotBefore:    time.Now().Add(-48 * time.Hour),
		NotAfter:     time.Now().Add(-24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create expired cert: %v", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})
}
