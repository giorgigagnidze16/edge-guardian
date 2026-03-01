// Package ota implements agent self-update and OTA binary deployment.
package ota

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"go.uber.org/zap"
)

// Updater handles OTA binary updates for the agent.
type Updater struct {
	dataDir    string
	binaryPath string
	logger     *zap.Logger
	httpClient *http.Client
}

// NewUpdater creates a new OTA updater.
func NewUpdater(dataDir string, logger *zap.Logger) *Updater {
	binaryPath, _ := os.Executable()
	return &Updater{
		dataDir:    dataDir,
		binaryPath: binaryPath,
		logger:     logger,
		httpClient: &http.Client{Timeout: 5 * time.Minute},
	}
}

// Download fetches the artifact from the given URL, streaming to disk.
// Returns the path to the downloaded staging file.
func (u *Updater) Download(url string) (string, error) {
	stagingPath := filepath.Join(u.dataDir, "agent-staging")

	resp, err := u.httpClient.Get(url)
	if err != nil {
		return "", fmt.Errorf("download: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("download: HTTP %d", resp.StatusCode)
	}

	f, err := os.Create(stagingPath)
	if err != nil {
		return "", fmt.Errorf("create staging file: %w", err)
	}
	defer f.Close()

	_, err = io.Copy(f, resp.Body)
	if err != nil {
		os.Remove(stagingPath)
		return "", fmt.Errorf("write staging file: %w", err)
	}

	u.logger.Info("OTA artifact downloaded", zap.String("path", stagingPath))
	return stagingPath, nil
}

// VerifySHA256 checks the SHA-256 hash of a file.
func VerifySHA256(path string, expectedHash string) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open file: %w", err)
	}
	defer f.Close()

	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return fmt.Errorf("hash file: %w", err)
	}

	actual := hex.EncodeToString(h.Sum(nil))
	if actual != expectedHash {
		return fmt.Errorf("SHA-256 mismatch: expected %s, got %s", expectedHash, actual)
	}
	return nil
}

// VerifyEd25519 checks the Ed25519 signature of a file.
func VerifyEd25519(path string, sigHex string, pubKey ed25519.PublicKey) error {
	if len(sigHex) == 0 {
		return nil // No signature to verify
	}

	sig, err := hex.DecodeString(sigHex)
	if err != nil {
		return fmt.Errorf("decode signature: %w", err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read file: %w", err)
	}

	if !ed25519.Verify(pubKey, data, sig) {
		return fmt.Errorf("Ed25519 signature verification failed")
	}
	return nil
}

// Apply moves the staging binary to a known location and signals the watchdog
// by exiting with code 42.
func (u *Updater) Apply(stagingPath string) error {
	// Mark the staging binary as executable
	if err := os.Chmod(stagingPath, 0755); err != nil {
		return fmt.Errorf("chmod staging binary: %w", err)
	}

	u.logger.Info("OTA update staged, exiting with code 42 for watchdog swap",
		zap.String("staging", stagingPath))

	// Exit with code 42 — the watchdog will detect this and swap the binary
	os.Exit(42)

	return nil // unreachable
}

// StagingPath returns the conventional path for the staged binary.
func (u *Updater) StagingPath() string {
	return filepath.Join(u.dataDir, "agent-staging")
}
