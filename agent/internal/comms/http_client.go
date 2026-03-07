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
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"github.com/edgeguardian/agent/internal/model"
	"go.uber.org/zap"
)

const (
	maxRetries     = 3
	initialBackoff = 1 * time.Second
)

// ControllerClient communicates with the EdgeGuardian controller over HTTP/JSON.
type ControllerClient struct {
	baseURL    string
	authToken  string
	httpClient *http.Client
	logger     *zap.Logger
}

// SetAuthToken sets the Bearer token used for authenticated API calls.
func (c *ControllerClient) SetAuthToken(token string) {
	c.authToken = token
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

// Heartbeat sends a heartbeat and receives manifest updates or commands.
func (c *ControllerClient) Heartbeat(ctx context.Context, req *model.HeartbeatRequest) (*model.HeartbeatResponse, error) {
	var resp model.HeartbeatResponse
	if err := c.doWithRetry(ctx, http.MethodPost, "/api/v1/agent/heartbeat", req, &resp); err != nil {
		return nil, fmt.Errorf("heartbeat failed: %w", err)
	}
	return &resp, nil
}

// GetDesiredState fetches the full desired-state manifest for the device.
func (c *ControllerClient) GetDesiredState(ctx context.Context, deviceID string) (*model.DesiredStateResponse, error) {
	var resp model.DesiredStateResponse
	path := fmt.Sprintf("/api/v1/agent/desired-state/%s", deviceID)
	if err := c.doWithRetry(ctx, http.MethodGet, path, nil, &resp); err != nil {
		return nil, fmt.Errorf("get desired state failed: %w", err)
	}
	return &resp, nil
}

// ReportState reports the observed state to the controller.
func (c *ControllerClient) ReportState(ctx context.Context, req *model.ReportStateRequest) error {
	var resp model.ReportStateResponse
	if err := c.doWithRetry(ctx, http.MethodPost, "/api/v1/agent/report-state", req, &resp); err != nil {
		return fmt.Errorf("report state failed: %w", err)
	}
	return nil
}

// Enroll sends an enrollment request with a token.
func (c *ControllerClient) Enroll(ctx context.Context, req *model.EnrollRequest) (*model.RegisterResponse, error) {
	var resp model.RegisterResponse
	if err := c.doWithRetry(ctx, http.MethodPost, "/api/v1/agent/enroll", req, &resp); err != nil {
		return nil, fmt.Errorf("enrollment failed: %w", err)
	}
	return &resp, nil
}

// BaseURL returns the controller's base URL (e.g. "http://localhost:8443").
func (c *ControllerClient) BaseURL() string {
	return c.baseURL
}

// ReportOTAStatus reports OTA update progress to the controller.
func (c *ControllerClient) ReportOTAStatus(ctx context.Context, report *model.OTAStatusReport) error {
	return c.doWithRetry(ctx, http.MethodPost, "/api/v1/agent/ota/status", report, nil)
}

// Close is a no-op for HTTP clients (kept for interface compatibility).
func (c *ControllerClient) Close() error {
	c.httpClient.CloseIdleConnections()
	return nil
}

// httpError wraps an HTTP response error with its status code, enabling
// the retry logic to distinguish retryable 5xx errors from non-retryable 4xx.
type httpError struct {
	StatusCode int
	Message    string
}

func (e *httpError) Error() string {
	return e.Message
}

// isRetryable returns true if the error is a network-level failure or a 5xx
// server error. Client errors (4xx) are never retried.
func isRetryable(err error) bool {
	if err == nil {
		return false
	}

	// 5xx server errors are retryable.
	var he *httpError
	if errors.As(err, &he) {
		return he.StatusCode >= 500
	}

	// Network-level errors (connection refused, timeout, DNS) are retryable.
	var netErr net.Error
	if errors.As(err, &netErr) {
		return true
	}

	// Treat any other transport error as potentially retryable.
	return true
}

// doWithRetry wraps doJSON with exponential backoff retries.
// Retries up to maxRetries times on network errors and 5xx responses.
// 4xx client errors are returned immediately without retry.
func (c *ControllerClient) doWithRetry(ctx context.Context, method, path string, body interface{}, result interface{}) error {
	var lastErr error
	backoff := initialBackoff

	for attempt := 0; attempt < maxRetries; attempt++ {
		lastErr = c.doJSON(ctx, method, path, body, result)
		if lastErr == nil {
			return nil
		}

		if !isRetryable(lastErr) {
			return lastErr
		}

		if attempt < maxRetries-1 {
			c.logger.Warn("request failed, retrying",
				zap.String("method", method),
				zap.String("path", path),
				zap.Int("attempt", attempt+1),
				zap.Duration("backoff", backoff),
				zap.Error(lastErr),
			)

			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(backoff):
			}

			backoff *= 2
		}
	}

	return fmt.Errorf("all %d attempts failed: %w", maxRetries, lastErr)
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

	if c.authToken != "" {
		req.Header.Set("X-Device-Token", c.authToken)
	}

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
		return &httpError{
			StatusCode: resp.StatusCode,
			Message:    fmt.Sprintf("HTTP %d from %s: %s", resp.StatusCode, path, string(respBody)),
		}
	}

	if result != nil && len(respBody) > 0 {
		if err := json.Unmarshal(respBody, result); err != nil {
			return fmt.Errorf("decode response from %s: %w", path, err)
		}
	}

	return nil
}
