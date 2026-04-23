package model

import (
	"encoding/json"
	"testing"
	"time"
)

func TestDeviceStatus_JSONRoundTrip(t *testing.T) {
	original := DeviceStatus{
		State:            "online",
		CPUUsagePercent:  42.5,
		MemoryUsedBytes:  2_147_483_648,
		MemoryTotalBytes: 4_294_967_296,
		DiskUsedBytes:    50_000_000_000,
		DiskTotalBytes:   100_000_000_000,
		UptimeSeconds:    86400,
		ReconcileStatus:  "converged",
	}

	data, err := json.Marshal(original)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var decoded DeviceStatus
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if decoded.State != original.State {
		t.Fatalf("State: got %q, want %q", decoded.State, original.State)
	}
	if decoded.CPUUsagePercent != original.CPUUsagePercent {
		t.Fatalf("CPU: got %f, want %f", decoded.CPUUsagePercent, original.CPUUsagePercent)
	}
	if decoded.MemoryUsedBytes != original.MemoryUsedBytes {
		t.Fatalf("MemUsed: got %d, want %d", decoded.MemoryUsedBytes, original.MemoryUsedBytes)
	}
	if decoded.MemoryTotalBytes != original.MemoryTotalBytes {
		t.Fatalf("MemTotal: got %d, want %d", decoded.MemoryTotalBytes, original.MemoryTotalBytes)
	}
	if decoded.UptimeSeconds != original.UptimeSeconds {
		t.Fatalf("Uptime: got %d, want %d", decoded.UptimeSeconds, original.UptimeSeconds)
	}
}

func TestDeviceManifest_JSONRoundTrip(t *testing.T) {
	original := DeviceManifest{
		APIVersion: "edgeguardian/v1",
		Kind:       "DeviceManifest",
		Metadata: ManifestMetadata{
			Name:   "sensor-42",
			Labels: map[string]string{"zone": "warehouse-a"},
		},
		Spec: ManifestSpec{
			Files: []FileResource{
				{Path: "/etc/app.conf", Content: "key=value", Mode: "0644", Owner: "root:root"},
			},
			Services: []ServiceResource{
				{Name: "nginx", Enabled: "true", State: "running"},
			},
		},
		Version: 7,
	}

	data, err := json.Marshal(original)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var decoded DeviceManifest
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if decoded.APIVersion != original.APIVersion {
		t.Fatalf("APIVersion: got %q, want %q", decoded.APIVersion, original.APIVersion)
	}
	if decoded.Metadata.Name != original.Metadata.Name {
		t.Fatalf("Metadata.Name: got %q, want %q", decoded.Metadata.Name, original.Metadata.Name)
	}
	if decoded.Metadata.Labels["zone"] != "warehouse-a" {
		t.Fatalf("Labels[zone]: got %q", decoded.Metadata.Labels["zone"])
	}
	if len(decoded.Spec.Files) != 1 {
		t.Fatalf("Files: got %d, want 1", len(decoded.Spec.Files))
	}
	if decoded.Spec.Files[0].Path != "/etc/app.conf" {
		t.Fatalf("File path: got %q", decoded.Spec.Files[0].Path)
	}
	if len(decoded.Spec.Services) != 1 {
		t.Fatalf("Services: got %d, want 1", len(decoded.Spec.Services))
	}
	if decoded.Spec.Services[0].Name != "nginx" {
		t.Fatalf("Service name: got %q", decoded.Spec.Services[0].Name)
	}
	if decoded.Version != 7 {
		t.Fatalf("Version: got %d, want 7", decoded.Version)
	}
}

func TestDeviceState_StringValues(t *testing.T) {
	tests := []struct {
		state string
		want  string
	}{
		{"online", "online"},
		{"degraded", "degraded"},
		{"offline", "offline"},
	}

	for _, tc := range tests {
		status := DeviceStatus{State: tc.state}
		data, err := json.Marshal(status)
		if err != nil {
			t.Fatalf("marshal: %v", err)
		}

		var decoded DeviceStatus
		if err := json.Unmarshal(data, &decoded); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}

		if decoded.State != tc.want {
			t.Errorf("State=%q: got %q, want %q", tc.state, decoded.State, tc.want)
		}
	}
}

func TestManifest_EmptyResources(t *testing.T) {
	m := DeviceManifest{
		APIVersion: "edgeguardian/v1",
		Kind:       "DeviceManifest",
		Spec:       ManifestSpec{},
	}

	data, err := json.Marshal(m)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var decoded DeviceManifest
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if decoded.Spec.Files != nil {
		t.Fatalf("expected nil Files, got %v", decoded.Spec.Files)
	}
	if decoded.Spec.Services != nil {
		t.Fatalf("expected nil Services, got %v", decoded.Spec.Services)
	}
}

func TestTelemetryMessage_JSONRoundTrip(t *testing.T) {
	original := TelemetryMessage{
		DeviceID:  "sensor-01",
		Timestamp: time.Now().Truncate(time.Second),
		Status: &DeviceStatus{
			State:           "online",
			CPUUsagePercent: 30.0,
		},
	}

	data, err := json.Marshal(original)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var decoded TelemetryMessage
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if decoded.DeviceID != original.DeviceID {
		t.Fatalf("DeviceID: got %q, want %q", decoded.DeviceID, original.DeviceID)
	}
	if decoded.Status.CPUUsagePercent != 30.0 {
		t.Fatalf("CPU: got %f, want 30.0", decoded.Status.CPUUsagePercent)
	}
}

func TestCommand_JSONRoundTrip(t *testing.T) {
	original := Command{
		ID:   "cmd-123",
		Type: "ota_update",
		Params: map[string]string{
			"version": "1.2.0",
			"url":     "https://example.com/agent-1.2.0",
		},
		CreatedAt: time.Now().Truncate(time.Second),
	}

	data, err := json.Marshal(original)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var decoded Command
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if decoded.ID != original.ID {
		t.Fatalf("ID: got %q, want %q", decoded.ID, original.ID)
	}
	if decoded.Type != original.Type {
		t.Fatalf("Type: got %q, want %q", decoded.Type, original.Type)
	}
	if decoded.Params["version"] != "1.2.0" {
		t.Fatalf("Params[version]: got %q", decoded.Params["version"])
	}
}
