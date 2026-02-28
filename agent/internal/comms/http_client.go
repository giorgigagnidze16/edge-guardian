// Package comms provides communication with the EdgeGuardian controller.
// This HTTP/JSON client replaces the original gRPC client to reduce binary
// size from ~12MB to <5MB. The controller exposes matching REST endpoints
// alongside its gRPC server so other clients (dashboard, CLI) can still
// use gRPC if desired.
package comms

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/edgeguardian/agent/internal/model"
	"go.uber.org/zap"
)

// ControllerClient communicates with the EdgeGuardian controller over HTTP/JSON.
type ControllerClient struct {
	baseURL    string
	httpClient *http.Client
	logger     *zap.Logger
}

// NewControllerClient creates a new HTTP client for the controller REST API.
func NewControllerClient(address string, port int, logger *zap.Logger) *ControllerClient {
	return &ControllerClient{
		baseURL: fmt.Sprintf("http://%s:%d", address, port),
		httpClient: &http.Client{
			Timeout: 15 * time.Second,
		},
		logger: logger,
	}
}

// Register sends a registration request to the controller.
func (c *ControllerClient) Register(ctx context.Context, req *model.RegisterRequest) (*model.RegisterResponse, error) {
	var resp model.RegisterResponse
	if err := c.doJSON(ctx, http.MethodPost, "/api/v1/agent/register", req, &resp); err != nil {
		return nil, fmt.Errorf("registration failed: %w", err)
	}
	return &resp, nil
}

// Heartbeat sends a heartbeat and receives manifest updates or commands.
func (c *ControllerClient) Heartbeat(ctx context.Context, req *model.HeartbeatRequest) (*model.HeartbeatResponse, error) {
	var resp model.HeartbeatResponse
	if err := c.doJSON(ctx, http.MethodPost, "/api/v1/agent/heartbeat", req, &resp); err != nil {
		return nil, fmt.Errorf("heartbeat failed: %w", err)
	}
	return &resp, nil
}

// GetDesiredState fetches the full desired-state manifest for the device.
func (c *ControllerClient) GetDesiredState(ctx context.Context, deviceID string) (*model.DesiredStateResponse, error) {
	var resp model.DesiredStateResponse
	path := fmt.Sprintf("/api/v1/agent/desired-state/%s", deviceID)
	if err := c.doJSON(ctx, http.MethodGet, path, nil, &resp); err != nil {
		return nil, fmt.Errorf("get desired state failed: %w", err)
	}
	return &resp, nil
}

// ReportState reports the observed state to the controller.
func (c *ControllerClient) ReportState(ctx context.Context, req *model.ReportStateRequest) error {
	var resp model.ReportStateResponse
	if err := c.doJSON(ctx, http.MethodPost, "/api/v1/agent/report-state", req, &resp); err != nil {
		return fmt.Errorf("report state failed: %w", err)
	}
	return nil
}

// Close is a no-op for HTTP clients (kept for interface compatibility).
func (c *ControllerClient) Close() error {
	c.httpClient.CloseIdleConnections()
	return nil
}

// doJSON performs an HTTP request with JSON body and decodes the JSON response.
func (c *ControllerClient) doJSON(ctx context.Context, method, path string, body interface{}, result interface{}) error {
	url := c.baseURL + path

	var bodyReader io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("marshal request body: %w", err)
		}
		bodyReader = bytes.NewReader(data)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}

	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("request to %s: %w", path, err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read response body: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d from %s: %s", resp.StatusCode, path, string(respBody))
	}

	if result != nil && len(respBody) > 0 {
		if err := json.Unmarshal(respBody, result); err != nil {
			return fmt.Errorf("decode response from %s: %w", path, err)
		}
	}

	return nil
}
