package comms

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"os"
)

// TLSConfig captures everything needed to build a *tls.Config for the mTLS MQTT broker.
type TLSConfig struct {
	CACertPath         string
	IdentityCertPath   string
	IdentityKeyPath    string
	ServerName         string
	InsecureSkipVerify bool
}

// IsZero reports whether any cert-related field is populated.
func (c TLSConfig) IsZero() bool {
	return c.CACertPath == "" && c.IdentityCertPath == "" && c.IdentityKeyPath == ""
}

// Build reads the on-disk cert material and produces a *tls.Config.
func (c TLSConfig) Build() (*tls.Config, error) {
	if c.CACertPath == "" {
		return nil, fmt.Errorf("mTLS requires ca_cert_path")
	}
	caBytes, err := os.ReadFile(c.CACertPath)
	if err != nil {
		return nil, fmt.Errorf("read ca cert %s: %w", c.CACertPath, err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caBytes) {
		return nil, fmt.Errorf("ca cert %s contained no parseable PEM blocks", c.CACertPath)
	}

	tlsCfg := &tls.Config{
		RootCAs:            pool,
		MinVersion:         tls.VersionTLS12,
		ServerName:         c.ServerName,
		InsecureSkipVerify: c.InsecureSkipVerify, //nolint:gosec // operator-controlled dev escape hatch
	}

	if c.IdentityCertPath != "" || c.IdentityKeyPath != "" {
		if c.IdentityCertPath == "" || c.IdentityKeyPath == "" {
			return nil, fmt.Errorf("identity_cert_path and identity_key_path must both be set")
		}
		keypair, err := tls.LoadX509KeyPair(c.IdentityCertPath, c.IdentityKeyPath)
		if err != nil {
			return nil, fmt.Errorf("load identity keypair (%s, %s): %w",
				c.IdentityCertPath, c.IdentityKeyPath, err)
		}
		tlsCfg.Certificates = []tls.Certificate{keypair}
	}
	return tlsCfg, nil
}
