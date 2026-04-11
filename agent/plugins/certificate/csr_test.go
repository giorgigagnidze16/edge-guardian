package certificate

import (
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"os"
	"path/filepath"
	"testing"
)

func TestGenerateKeyPair_ECDSA(t *testing.T) {
	key, err := GenerateKeyPair("ecdsa-p256")
	if err != nil {
		t.Fatalf("GenerateKeyPair: %v", err)
	}
	if _, ok := key.(*ecdsa.PrivateKey); !ok {
		t.Fatalf("expected *ecdsa.PrivateKey, got %T", key)
	}
}

func TestGenerateKeyPair_Default(t *testing.T) {
	key, err := GenerateKeyPair("")
	if err != nil {
		t.Fatalf("GenerateKeyPair: %v", err)
	}
	if _, ok := key.(*ecdsa.PrivateKey); !ok {
		t.Fatalf("expected *ecdsa.PrivateKey for default, got %T", key)
	}
}

func TestGenerateKeyPair_RSA(t *testing.T) {
	key, err := GenerateKeyPair("rsa-2048")
	if err != nil {
		t.Fatalf("GenerateKeyPair: %v", err)
	}
	if _, ok := key.(*rsa.PrivateKey); !ok {
		t.Fatalf("expected *rsa.PrivateKey, got %T", key)
	}
}

func TestGenerateKeyPair_Invalid(t *testing.T) {
	_, err := GenerateKeyPair("ed25519-unsupported")
	if err == nil {
		t.Fatal("expected error for unsupported algorithm")
	}
}

func TestCreateCSR(t *testing.T) {
	key, err := GenerateKeyPair("ecdsa-p256")
	if err != nil {
		t.Fatalf("keygen: %v", err)
	}

	csrPEM, err := CreateCSR(key, "device-001.edgeguardian.local", []string{"10.0.1.5", "myapp.local"})
	if err != nil {
		t.Fatalf("CreateCSR: %v", err)
	}

	block, _ := pem.Decode(csrPEM)
	if block == nil {
		t.Fatal("failed to decode PEM")
	}
	if block.Type != "CERTIFICATE REQUEST" {
		t.Fatalf("expected CERTIFICATE REQUEST, got %s", block.Type)
	}

	csr, err := x509.ParseCertificateRequest(block.Bytes)
	if err != nil {
		t.Fatalf("ParseCertificateRequest: %v", err)
	}
	if csr.Subject.CommonName != "device-001.edgeguardian.local" {
		t.Fatalf("expected CN 'device-001.edgeguardian.local', got %q", csr.Subject.CommonName)
	}
	if len(csr.IPAddresses) != 1 || csr.IPAddresses[0].String() != "10.0.1.5" {
		t.Fatalf("expected IP SAN 10.0.1.5, got %v", csr.IPAddresses)
	}
	if len(csr.DNSNames) != 1 || csr.DNSNames[0] != "myapp.local" {
		t.Fatalf("expected DNS SAN myapp.local, got %v", csr.DNSNames)
	}
}

func TestCreateCSR_NoSANs(t *testing.T) {
	key, _ := GenerateKeyPair("")
	csrPEM, err := CreateCSR(key, "simple-device", nil)
	if err != nil {
		t.Fatalf("CreateCSR: %v", err)
	}

	block, _ := pem.Decode(csrPEM)
	csr, err := x509.ParseCertificateRequest(block.Bytes)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if csr.Subject.CommonName != "simple-device" {
		t.Fatalf("unexpected CN: %q", csr.Subject.CommonName)
	}
	if len(csr.IPAddresses) != 0 || len(csr.DNSNames) != 0 {
		t.Fatal("expected no SANs")
	}
}

func TestMarshalAndLoadPrivateKey(t *testing.T) {
	key, _ := GenerateKeyPair("ecdsa-p256")
	keyPEM, err := MarshalPrivateKey(key)
	if err != nil {
		t.Fatalf("MarshalPrivateKey: %v", err)
	}

	dir := t.TempDir()
	path := filepath.Join(dir, "key.pem")
	if err := os.WriteFile(path, keyPEM, 0600); err != nil {
		t.Fatalf("write: %v", err)
	}

	loaded, err := LoadPrivateKey(path)
	if err != nil {
		t.Fatalf("LoadPrivateKey: %v", err)
	}

	ecKey, ok := loaded.(*ecdsa.PrivateKey)
	if !ok {
		t.Fatalf("expected *ecdsa.PrivateKey, got %T", loaded)
	}

	// Verify loaded key can create a CSR
	_, err = CreateCSR(ecKey, "test", nil)
	if err != nil {
		t.Fatalf("CSR with loaded key: %v", err)
	}
}

func TestWriteAtomic(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "test.txt")

	if err := WriteAtomic(path, []byte("hello"), 0644); err != nil {
		t.Fatalf("WriteAtomic: %v", err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(data) != "hello" {
		t.Fatalf("expected 'hello', got %q", string(data))
	}

	// Verify no temp file left behind
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temp file should not exist")
	}
}
