package certificate

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"net"
	"os"
)

// Fingerprint returns the SHA-256 hex digest of the raw PEM bytes. Used for logs and
// telemetry so operators can tell whether an agent is actually running the cert they expect.
func Fingerprint(pemBytes []byte) string {
	sum := sha256.Sum256(pemBytes)
	return hex.EncodeToString(sum[:])
}

// GenerateKeyPair creates a new private key. Supported algorithms: "ecdsa-p256" (default), "rsa-2048".
func GenerateKeyPair(algo string) (crypto.PrivateKey, error) {
	switch algo {
	case "rsa-2048":
		return rsa.GenerateKey(rand.Reader, 2048)
	case "ecdsa-p256", "":
		return ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	default:
		return nil, fmt.Errorf("unsupported key algorithm: %s", algo)
	}
}

// CreateCSR generates a PEM-encoded PKCS#10 Certificate Signing Request.
func CreateCSR(key crypto.PrivateKey, cn string, sans []string) ([]byte, error) {
	template := &x509.CertificateRequest{
		Subject: pkix.Name{CommonName: cn},
	}

	for _, san := range sans {
		if ip := net.ParseIP(san); ip != nil {
			template.IPAddresses = append(template.IPAddresses, ip)
		} else {
			template.DNSNames = append(template.DNSNames, san)
		}
	}

	csrDER, err := x509.CreateCertificateRequest(rand.Reader, template, key)
	if err != nil {
		return nil, fmt.Errorf("create CSR: %w", err)
	}

	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrDER}), nil
}

// ParseCertificatePEM parses a PEM-encoded X.509 certificate.
func ParseCertificatePEM(certPEM []byte) (*x509.Certificate, error) {
	block, _ := pem.Decode(certPEM)
	if block == nil {
		return nil, fmt.Errorf("failed to decode PEM block")
	}
	return x509.ParseCertificate(block.Bytes)
}

// MarshalPrivateKey encodes a private key to PEM format (PKCS#8).
func MarshalPrivateKey(key crypto.PrivateKey) ([]byte, error) {
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, fmt.Errorf("marshal private key: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), nil
}

// LoadPrivateKey reads and parses a PEM-encoded private key from disk.
func LoadPrivateKey(path string) (crypto.PrivateKey, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("no PEM block found in %s", path)
	}

	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse private key: %w", err)
	}
	return key, nil
}

// WriteAtomic writes data to a temporary file and renames it to the target path.
func WriteAtomic(path string, data []byte, perm os.FileMode) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, perm); err != nil {
		return fmt.Errorf("write temp file: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("rename: %w", err)
	}
	return nil
}
