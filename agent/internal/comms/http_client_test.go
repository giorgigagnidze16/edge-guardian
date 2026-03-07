package comms

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/edgeguardian/agent/internal/model"
	"go.uber.org/zap"
)

func testLogger() *zap.Logger {
	logger, _ := zap.NewDevelopment()
	return logger
}

// newTestClient creates a ControllerClient pointed at the given test server.
func newTestClient(t *testing.T, serverURL string) *ControllerClient {
	t.Helper()
	return &ControllerClient{
		baseURL:    serverURL,
		httpClient: &http.Client{Timeout: 5 * time.Second},
		logger:     testLogger(),
	}
}

func TestEnroll_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/v1/agent/enroll" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}

		var req model.EnrollRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			t.Errorf("decode request: %v", err)
		}
		if req.DeviceID != "sensor-01" {
			t.Errorf("expected deviceId=sensor-01, got %q", req.DeviceID)
		}
		if req.EnrollmentToken != "egt_test-token" {
			t.Errorf("expected enrollmentToken=egt_test-token, got %q", req.EnrollmentToken)
		}

		json.NewEncoder(w).Encode(model.RegisterResponse{
			Accepted:    true,
			Message:     "enrolled",
			DeviceToken: "edt_generated-device-token",
		})
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	resp, err := client.Enroll(context.Background(), &model.EnrollRequest{
		EnrollmentToken: "egt_test-token",
		DeviceID:        "sensor-01",
		Hostname:        "host1",
		Architecture:    "amd64",
		OS:              "linux",
		AgentVersion:    "0.3.0",
	})
	if err != nil {
		t.Fatalf("Enroll: %v", err)
	}
	if !resp.Accepted {
		t.Fatal("expected Accepted=true")
	}
	if resp.DeviceToken != "edt_generated-device-token" {
		t.Fatalf("expected device token, got %q", resp.DeviceToken)
	}
}

func TestEnroll_InvalidToken(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"Invalid enrollment token"}`))
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	_, err := client.Enroll(context.Background(), &model.EnrollRequest{
		EnrollmentToken: "egt_bad-token",
		DeviceID:        "sensor-01",
	})
	if err == nil {
		t.Fatal("expected error for 401")
	}

	var he *httpError
	if !errors.As(err, &he) {
		t.Fatalf("expected httpError, got %T: %v", err, err)
	}
	if he.StatusCode != 401 {
		t.Fatalf("expected status 401, got %d", he.StatusCode)
	}
}

func TestEnroll_ServerError_Retries(t *testing.T) {
	attempts := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts < 3 {
			w.WriteHeader(http.StatusInternalServerError)
			w.Write([]byte("internal error"))
			return
		}
		json.NewEncoder(w).Encode(model.RegisterResponse{
			Accepted:    true,
			DeviceToken: "edt_after-retry",
		})
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	resp, err := client.Enroll(context.Background(), &model.EnrollRequest{
		EnrollmentToken: "egt_test",
		DeviceID:        "retry-me",
	})
	if err != nil {
		t.Fatalf("Enroll after retries: %v", err)
	}
	if !resp.Accepted {
		t.Fatal("expected Accepted=true after retries")
	}
	if attempts != 3 {
		t.Fatalf("expected 3 attempts, got %d", attempts)
	}
}

func TestEnroll_ConnectionRefused(t *testing.T) {
	client := newTestClient(t, "http://127.0.0.1:1") // nothing listening
	_, err := client.Enroll(context.Background(), &model.EnrollRequest{
		EnrollmentToken: "egt_test",
		DeviceID:        "x",
	})
	if err == nil {
		t.Fatal("expected connection error")
	}
}

func TestEnroll_SetsAuthToken(t *testing.T) {
	var receivedToken string
	callCount := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		callCount++
		receivedToken = r.Header.Get("X-Device-Token")
		if r.URL.Path == "/api/v1/agent/enroll" {
			json.NewEncoder(w).Encode(model.RegisterResponse{
				Accepted:    true,
				DeviceToken: "edt_new-token",
			})
		} else {
			json.NewEncoder(w).Encode(model.HeartbeatResponse{})
		}
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)

	// Enroll — no device token header expected
	_, err := client.Enroll(context.Background(), &model.EnrollRequest{
		EnrollmentToken: "egt_test",
		DeviceID:        "sensor-01",
	})
	if err != nil {
		t.Fatalf("Enroll: %v", err)
	}
	if receivedToken != "" {
		t.Fatalf("expected no device token header on enroll, got %q", receivedToken)
	}

	// Set auth token and make a heartbeat
	client.SetAuthToken("edt_new-token")
	_, err = client.Heartbeat(context.Background(), &model.HeartbeatRequest{
		DeviceID:  "sensor-01",
		Timestamp: time.Now(),
	})
	if err != nil {
		t.Fatalf("Heartbeat: %v", err)
	}
	if receivedToken != "edt_new-token" {
		t.Fatalf("expected X-Device-Token=edt_new-token, got %q", receivedToken)
	}
}

func TestHeartbeat_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/agent/heartbeat" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		json.NewEncoder(w).Encode(model.HeartbeatResponse{
			ManifestUpdated: true,
			Manifest: &model.DeviceManifest{
				Version: 5,
			},
		})
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	resp, err := client.Heartbeat(context.Background(), &model.HeartbeatRequest{
		DeviceID:  "sensor-01",
		Timestamp: time.Now(),
	})
	if err != nil {
		t.Fatalf("Heartbeat: %v", err)
	}
	if !resp.ManifestUpdated {
		t.Fatal("expected ManifestUpdated=true")
	}
	if resp.Manifest == nil || resp.Manifest.Version != 5 {
		t.Fatalf("expected manifest version 5, got %+v", resp.Manifest)
	}
}

func TestHeartbeat_Unauthorized(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte("unauthorized"))
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	_, err := client.Heartbeat(context.Background(), &model.HeartbeatRequest{DeviceID: "x"})
	if err == nil {
		t.Fatal("expected error for 401")
	}

	var he *httpError
	if !errors.As(err, &he) {
		t.Fatalf("expected httpError, got %T", err)
	}
	if he.StatusCode != 401 {
		t.Fatalf("expected status 401, got %d", he.StatusCode)
	}
}

func TestGetDesiredState_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/v1/agent/desired-state/sensor-01" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}

		json.NewEncoder(w).Encode(model.DesiredStateResponse{
			Version: 10,
			Manifest: &model.DeviceManifest{
				APIVersion: "edgeguardian/v1",
				Kind:       "DeviceManifest",
				Spec: model.ManifestSpec{
					Files: []model.FileResource{
						{Path: "/etc/app.conf", Content: "key=value", Mode: "0644"},
					},
				},
			},
		})
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	resp, err := client.GetDesiredState(context.Background(), "sensor-01")
	if err != nil {
		t.Fatalf("GetDesiredState: %v", err)
	}
	if resp.Version != 10 {
		t.Fatalf("expected version 10, got %d", resp.Version)
	}
	if resp.Manifest == nil {
		t.Fatal("expected non-nil manifest")
	}
	if len(resp.Manifest.Spec.Files) != 1 {
		t.Fatalf("expected 1 file, got %d", len(resp.Manifest.Spec.Files))
	}
	if resp.Manifest.Spec.Files[0].Path != "/etc/app.conf" {
		t.Fatalf("expected path /etc/app.conf, got %q", resp.Manifest.Spec.Files[0].Path)
	}
}

func TestGetDesiredState_NotFound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("not found"))
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	_, err := client.GetDesiredState(context.Background(), "nonexistent")
	if err == nil {
		t.Fatal("expected error for 404")
	}
}

func TestReportState_Success(t *testing.T) {
	var receivedReq model.ReportStateRequest
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		json.NewDecoder(r.Body).Decode(&receivedReq)
		json.NewEncoder(w).Encode(model.ReportStateResponse{Acknowledged: true})
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	err := client.ReportState(context.Background(), &model.ReportStateRequest{
		DeviceID: "sensor-01",
		Status: &model.DeviceStatus{
			State:           "online",
			CPUUsagePercent: 25.5,
		},
		Timestamp: time.Now(),
	})
	if err != nil {
		t.Fatalf("ReportState: %v", err)
	}
	if receivedReq.DeviceID != "sensor-01" {
		t.Fatalf("expected deviceId=sensor-01, got %q", receivedReq.DeviceID)
	}
	if receivedReq.Status.CPUUsagePercent != 25.5 {
		t.Fatalf("expected CPU=25.5, got %.1f", receivedReq.Status.CPUUsagePercent)
	}
}

func TestHTTPClient_ContentTypeHeaders(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ct := r.Header.Get("Content-Type")
		accept := r.Header.Get("Accept")
		if r.Method == http.MethodPost && ct != "application/json" {
			t.Errorf("expected Content-Type=application/json, got %q", ct)
		}
		if accept != "application/json" {
			t.Errorf("expected Accept=application/json, got %q", accept)
		}
		json.NewEncoder(w).Encode(model.RegisterResponse{Accepted: true})
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	client.Enroll(context.Background(), &model.EnrollRequest{
		EnrollmentToken: "egt_test",
		DeviceID:        "h",
	})
}

func TestHTTPClient_ContextCancellation(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(5 * time.Second)
		json.NewEncoder(w).Encode(model.RegisterResponse{})
	}))
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()

	client := newTestClient(t, server.URL)
	_, err := client.Enroll(ctx, &model.EnrollRequest{
		EnrollmentToken: "egt_test",
		DeviceID:        "timeout",
	})
	if err == nil {
		t.Fatal("expected error from cancelled context")
	}
}

func TestHTTPClient_EmptyResponseBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		// Empty body.
	}))
	defer server.Close()

	client := newTestClient(t, server.URL)
	// ReportState should handle empty body gracefully since ReportStateResponse
	// just has Acknowledged bool (zero-value is fine).
	err := client.ReportState(context.Background(), &model.ReportStateRequest{DeviceID: "x"})
	if err != nil {
		t.Fatalf("expected no error for empty response body, got: %v", err)
	}
}

func TestHTTPClient_Close(t *testing.T) {
	client := newTestClient(t, "http://localhost:1")
	if err := client.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}
