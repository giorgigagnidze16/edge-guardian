// Package ota implements agent self-update and OTA binary deployment.
package ota

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"go.uber.org/zap"
)

// UpdateMarker records an in-flight OTA update for post-restart detection.
type UpdateMarker struct {
	DeploymentID    int64  `json:"deploymentId"`
	PreviousVersion string `json:"previousVersion"`
	Timestamp       string `json:"timestamp"`
}

// Updater handles OTA binary updates for the agent.
type Updater struct {
	dataDir       string
	binaryPath    string
	logger        *zap.Logger
	httpClient    *http.Client
	ed25519PubKey ed25519.PublicKey
}

// NewUpdater creates a new OTA updater. signKeyHex is the hex-encoded Ed25519
// public key for signature verification (empty string to skip verification).
func NewUpdater(dataDir, signKeyHex string, logger *zap.Logger) *Updater {
	binaryPath, _ := os.Executable()
	u := &Updater{
		dataDir:    dataDir,
		binaryPath: binaryPath,
		logger:     logger,
		httpClient: &http.Client{Timeout: 5 * time.Minute},
	}
	if signKeyHex != "" {
		key, err := hex.DecodeString(signKeyHex)
		if err == nil && len(key) == ed25519.PublicKeySize {
			u.ed25519PubKey = ed25519.PublicKey(key)
		} else {
			logger.Warn("invalid Ed25519 public key, signature verification disabled",
				zap.String("key_hex", signKeyHex))
		}
	}
	return u
}

// DataDir returns the data directory path.
func (u *Updater) DataDir() string {
	return u.dataDir
}

// Ed25519PublicKey returns the configured public key, or nil if not set.
func (u *Updater) Ed25519PublicKey() ed25519.PublicKey {
	return u.ed25519PubKey
}

// Download fetches the artifact from the given URL, streaming to disk.
func (u *Updater) Download(url string) (string, error) {
	stagingPath := u.StagingPath()

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

	if _, err = io.Copy(f, resp.Body); err != nil {
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
	if err := os.Chmod(stagingPath, 0755); err != nil {
		return fmt.Errorf("chmod staging binary: %w", err)
	}

	u.logger.Info("OTA update staged, exiting with code 42 for watchdog swap",
		zap.String("staging", stagingPath))

	os.Exit(42)
	return nil // unreachable
}

// StagingPath returns the conventional path for the staged binary.
func (u *Updater) StagingPath() string {
	return filepath.Join(u.dataDir, "agent-staging")
}

// WriteUpdateMarker persists a marker file so the agent can detect a
// successful update after restart.
func (u *Updater) WriteUpdateMarker(deploymentID int64, previousVersion string) error {
	marker := UpdateMarker{
		DeploymentID:    deploymentID,
		PreviousVersion: previousVersion,
		Timestamp:       time.Now().UTC().Format(time.RFC3339),
	}
	data, err := json.Marshal(marker)
	if err != nil {
		return err
	}
	return os.WriteFile(u.markerPath(), data, 0644)
}

// ReadUpdateMarker reads the update marker if it exists.
func (u *Updater) ReadUpdateMarker() (*UpdateMarker, error) {
	data, err := os.ReadFile(u.markerPath())
	if err != nil {
		return nil, err
	}
	var marker UpdateMarker
	if err := json.Unmarshal(data, &marker); err != nil {
		return nil, err
	}
	return &marker, nil
}

// ClearUpdateMarker removes the update marker file.
func (u *Updater) ClearUpdateMarker() error {
	return os.Remove(u.markerPath())
}

// ReadRollbackMarker checks if a rollback marker exists and returns the
// deployment ID from the associated update marker, if available.
func (u *Updater) ReadRollbackMarker() (bool, error) {
	_, err := os.Stat(u.rollbackMarkerPath())
	if os.IsNotExist(err) {
		return false, nil
	}
	return err == nil, err
}

// ClearRollbackMarker removes the rollback marker file.
func (u *Updater) ClearRollbackMarker() error {
	return os.Remove(u.rollbackMarkerPath())
}

func (u *Updater) markerPath() string {
	return filepath.Join(u.dataDir, "ota-update-marker.json")
}

func (u *Updater) rollbackMarkerPath() string {
	return filepath.Join(u.dataDir, "ota-rollback-marker")
}