package ota

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"runtime"
	"strings"

	"go.uber.org/zap"
)

// ReleaseInfo is the controller's published agent release manifest, served by
// GET /api/v1/agent/latest-version. Field names match the controller's
// AgentReleaseInfo DTO.
type ReleaseInfo struct {
	Version    string `json:"version"`
	SHA256     string `json:"sha256"`
	Ed25519Sig string `json:"ed25519Sig"`
}

// Puller checks the controller for a newer agent build and applies it through
// the watchdog binary-swap path: download -> verify -> stage -> exit 42.
type Puller struct {
	updater *Updater
	baseURL string
	os      string
	arch    string
	current string
	logger  *zap.Logger
}

// NewPuller builds a Puller. baseURL is the controller HTTPS base
// (e.g. https://controller.example.com:8443); current is the running version.
func NewPuller(updater *Updater, baseURL, current string, logger *zap.Logger) *Puller {
	return &Puller{
		updater: updater,
		baseURL: strings.TrimRight(baseURL, "/"),
		os:      runtime.GOOS,
		arch:    runtime.GOARCH,
		current: current,
		logger:  logger,
	}
}

// LatestRelease fetches the controller's current published release for this
// device's os/arch.
func (p *Puller) LatestRelease() (*ReleaseInfo, error) {
	endpoint := fmt.Sprintf("%s/api/v1/agent/latest-version?os=%s&arch=%s",
		p.baseURL, url.QueryEscape(p.os), url.QueryEscape(p.arch))

	resp, err := p.updater.httpClient.Get(endpoint)
	if err != nil {
		return nil, fmt.Errorf("fetch latest version: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("fetch latest version: HTTP %d", resp.StatusCode)
	}

	var rel ReleaseInfo
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return nil, fmt.Errorf("decode latest version: %w", err)
	}
	return &rel, nil
}

// Outdated reports whether rel names a different version than the running agent.
func (p *Puller) Outdated(rel *ReleaseInfo) bool {
	return rel != nil && rel.Version != "" && rel.Version != p.current
}

// Apply downloads, verifies, and stages rel, then exits with code 42 so the
// watchdog swaps the binary. It does not return on success.
func (p *Puller) Apply(rel *ReleaseInfo) error {
	binURL := fmt.Sprintf("%s/api/v1/agent/binary?os=%s&arch=%s",
		p.baseURL, url.QueryEscape(p.os), url.QueryEscape(p.arch))

	p.logger.Info("downloading agent update",
		zap.String("from", p.current), zap.String("to", rel.Version))

	staging, err := p.updater.Download(binURL)
	if err != nil {
		return fmt.Errorf("download agent binary: %w", err)
	}

	if rel.SHA256 != "" {
		if err := VerifySHA256(staging, rel.SHA256); err != nil {
			os.Remove(staging)
			return fmt.Errorf("sha256 verify: %w", err)
		}
	}
	if p.updater.Ed25519PublicKey() != nil {
		if err := VerifyEd25519(staging, rel.Ed25519Sig, p.updater.Ed25519PublicKey()); err != nil {
			os.Remove(staging)
			return fmt.Errorf("ed25519 verify: %w", err)
		}
	}

	if err := p.updater.WriteUpdateMarker(0, p.current); err != nil {
		p.logger.Warn("failed to write update marker", zap.Error(err))
	}
	return p.updater.Apply(staging)
}

// CheckAndApply fetches the latest release and applies it if newer. It returns
// (false, nil) when already up to date. On a successful update it does not
// return - the process exits with code 42 for the watchdog to swap.
func (p *Puller) CheckAndApply() (bool, error) {
	rel, err := p.LatestRelease()
	if err != nil {
		return false, err
	}
	if !p.Outdated(rel) {
		return false, nil
	}
	return true, p.Apply(rel)
}
