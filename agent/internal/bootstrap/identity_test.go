package bootstrap

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"testing"
	"time"

	"github.com/edgeguardian/agent/internal/model"
	"github.com/edgeguardian/agent/plugins/certificate"
)

// stubEnroller lets us assert that the manager sent the CSR we expect and returns a
// pre-baked signed cert without a live controller.
type stubEnroller struct {
	receivedReq   *model.EnrollRequest
	fixedResponse *model.RegisterResponse
	err           error
}

func (s *stubEnroller) Enroll(req *model.EnrollRequest) (*model.RegisterResponse, error) {
	s.receivedReq = req
	if s.err != nil {
		return nil, s.err
	}
	return s.fixedResponse, nil
}

func TestManager_Load_MissingIdentity(t *testing.T) {
	dir := t.TempDir()
	mgr, err := NewManager(dir)
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}
	id, err := mgr.Load()
	if err != nil {
		t.Fatalf("Load on empty dir should not error: %v", err)
	}
	if id != nil {
		t.Fatalf("expected nil identity, got %+v", id)
	}
}

func TestManager_Enroll_PersistsIdentity(t *testing.T) {
	dir := t.TempDir()
	mgr, err := NewManager(dir)
	if err != nil {
		t.Fatalf("NewManager: %v", err)
	}

	caPEM, signer := newTestCA(t)
	leafPEM := signLeaf(t, signer, "test-device", 30*24*time.Hour)

	stub := &stubEnroller{
		fixedResponse: &model.RegisterResponse{
			Accepted:           true,
			DeviceToken:        "edt_test",
			CaCertPem:          string(caPEM),
			IdentityCertPem:    string(leafPEM),
			IdentityCertSerial: "abc123",
		},
	}

	baseReq := &model.EnrollRequest{
		EnrollmentToken: "egt_test",
		DeviceID:        "test-device",
		Hostname:        "localhost",
	}

	id, err := mgr.Enroll(stub, baseReq, "test-device", nil)
	if err != nil {
		t.Fatalf("Enroll: %v", err)
	}
	if id == nil {
		t.Fatal("Enroll returned nil identity")
	}
	if stub.receivedReq == nil || stub.receivedReq.CsrPem == "" {
		t.Fatal("enroller never saw a CSR - manager didn't build one")
	}
	if stub.receivedReq.CommonName != "test-device" {
		t.Fatalf("wrong CN: %q", stub.receivedReq.CommonName)
	}

	// Reload from disk - proves we persisted, not just returned from memory.
	reloaded, err := mgr.Load()
	if err != nil {
		t.Fatalf("Load after Enroll: %v", err)
	}
	if reloaded == nil {
		t.Fatal("Load after Enroll returned nil")
	}
	if reloaded.Certificate.Subject.CommonName != "test-device" {
		t.Fatalf("persisted cert has wrong CN: %s", reloaded.Certificate.Subject.CommonName)
	}
}

func TestManager_Enroll_RejectsEmptyCert(t *testing.T) {
	dir := t.TempDir()
	mgr, _ := NewManager(dir)

	stub := &stubEnroller{
		fixedResponse: &model.RegisterResponse{
			Accepted:  true,
			CaCertPem: "", // deliberately empty
		},
	}
	_, err := mgr.Enroll(stub,
		&model.EnrollRequest{EnrollmentToken: "egt", DeviceID: "d"},
		"d", nil)
	if err == nil {
		t.Fatal("expected error when server returned empty identity/ca cert")
	}
}

func TestManager_Enroll_SurfacesTransportError(t *testing.T) {
	dir := t.TempDir()
	mgr, _ := NewManager(dir)

	stub := &stubEnroller{err: errors.New("broker unreachable")}
	_, err := mgr.Enroll(stub,
		&model.EnrollRequest{EnrollmentToken: "egt", DeviceID: "d"},
		"d", nil)
	if err == nil {
		t.Fatal("expected transport error to propagate")
	}
}

func TestIdentity_ShouldRenew(t *testing.T) {
	certPEM := signLeaf(t, newSignerOrDie(t), "d", 5*24*time.Hour) // expires in 5 days
	cert, _ := certificate.ParseCertificatePEM(certPEM)
	id := &Identity{Certificate: cert, NotAfter: cert.NotAfter}

	if !id.ShouldRenew(14 * 24 * time.Hour) {
		t.Fatal("cert 5 days from expiry should be renewable with 14-day window")
	}
	if id.ShouldRenew(1 * 24 * time.Hour) {
		t.Fatal("cert 5 days from expiry should NOT be renewable with 1-day window")
	}
	if (*Identity)(nil).ShouldRenew(1 * time.Hour) {
		t.Fatal("nil identity should return false, not panic")
	}
}

// ---- helpers -----------------------------------------------------------------------

type testSigner struct {
	cert *x509.Certificate
	key  *ecdsa.PrivateKey
}

func newSignerOrDie(t *testing.T) *testSigner {
	t.Helper()
	_, s := newTestCA(t)
	return s
}

func newTestCA(t *testing.T) ([]byte, *testSigner) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate CA key: %v", err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "Test CA"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(10 * 365 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("self-sign CA: %v", err)
	}
	cert, _ := x509.ParseCertificate(der)
	caPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	return caPEM, &testSigner{cert: cert, key: key}
}

func signLeaf(t *testing.T, signer *testSigner, cn string, validFor time.Duration) []byte {
	t.Helper()
	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("leaf key: %v", err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: cn},
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     time.Now().Add(validFor),
	}
	der, err := x509.CreateCertificate(rand.Reader, template, signer.cert, &leafKey.PublicKey, signer.key)
	if err != nil {
		t.Fatalf("sign leaf: %v", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
}
