// Package model defines the core data types for EdgeGuardian agent.
// These are plain Go structs with JSON tags that serve as the contract
// between the agent and controller over HTTP/JSON.
package model

import "time"

// DeviceManifest is the desired-state declaration for a device.
type DeviceManifest struct {
	APIVersion string           `json:"apiVersion" yaml:"apiVersion"`
	Kind       string           `json:"kind" yaml:"kind"`
	Metadata   ManifestMetadata `json:"metadata" yaml:"metadata"`
	Spec       ManifestSpec     `json:"spec" yaml:"spec"`
	Version    int64            `json:"version" yaml:"version"`
}

// ManifestMetadata holds identifying information about a manifest.
type ManifestMetadata struct {
	Name        string            `json:"name" yaml:"name"`
	Labels      map[string]string `json:"labels,omitempty" yaml:"labels,omitempty"`
	Annotations map[string]string `json:"annotations,omitempty" yaml:"annotations,omitempty"`
}

// ManifestSpec defines the desired resources on the device.
type ManifestSpec struct {
	Files    []FileResource    `json:"files,omitempty" yaml:"files,omitempty"`
	Services []ServiceResource `json:"services,omitempty" yaml:"services,omitempty"`
	Health   *HealthCheckSpec  `json:"health,omitempty" yaml:"health,omitempty"`
}

// FileResource declares a file that should exist with specific content and permissions.
type FileResource struct {
	Path    string `json:"path" yaml:"path"`
	Content string `json:"content" yaml:"content"`
	Mode    string `json:"mode" yaml:"mode"`   // e.g. "0644"
	Owner   string `json:"owner" yaml:"owner"` // e.g. "root:root"
}

// ServiceResource declares a systemd service and its desired state.
type ServiceResource struct {
	Name    string `json:"name" yaml:"name"`
	Enabled string `json:"enabled" yaml:"enabled"` // "true" / "false"
	State   string `json:"state" yaml:"state"`     // "running" / "stopped"
}

// HealthCheckSpec configures health checking for the device.
type HealthCheckSpec struct {
	IntervalSeconds int32         `json:"intervalSeconds" yaml:"interval_seconds"`
	Checks          []HealthCheck `json:"checks,omitempty" yaml:"checks,omitempty"`
}

// HealthCheck defines a single health probe.
type HealthCheck struct {
	Name           string `json:"name" yaml:"name"`
	Type           string `json:"type" yaml:"type"` // "http", "tcp", "exec"
	Target         string `json:"target" yaml:"target"`
	TimeoutSeconds int32  `json:"timeoutSeconds" yaml:"timeout_seconds"`
}

// DeviceStatus holds runtime metrics reported by the agent.
type DeviceStatus struct {
	State              string  `json:"state"` // "online", "degraded", "offline"
	CPUUsagePercent    float64 `json:"cpuUsagePercent"`
	MemoryUsedBytes    int64   `json:"memoryUsedBytes"`
	MemoryTotalBytes   int64   `json:"memoryTotalBytes"`
	DiskUsedBytes      int64   `json:"diskUsedBytes"`
	DiskTotalBytes     int64   `json:"diskTotalBytes"`
	TemperatureCelsius float64 `json:"temperatureCelsius"`
	UptimeSeconds      int64   `json:"uptimeSeconds"`
	LastReconcile      string  `json:"lastReconcile,omitempty"` // RFC3339
	ReconcileStatus    string  `json:"reconcileStatus"`         // "converged", "drifted", "error"
}

// PluginState reports per-plugin reconciliation results.
type PluginState struct {
	PluginName string            `json:"pluginName"`
	Status     string            `json:"status"`
	Details    map[string]string `json:"details,omitempty"`
}

// Command represents a controller-issued command for the agent.
type Command struct {
	ID        string            `json:"id"`
	Type      string            `json:"type"` // "ota_update", "restart", "exec", "vpn_configure"
	Params    map[string]string `json:"params,omitempty"`
	CreatedAt time.Time         `json:"createdAt"`
}

// RegisterRequest is sent by the agent to register with the controller.
type RegisterRequest struct {
	DeviceID     string            `json:"deviceId"`
	Hostname     string            `json:"hostname"`
	Architecture string            `json:"architecture"`
	OS           string            `json:"os"`
	AgentVersion string            `json:"agentVersion"`
	Labels       map[string]string `json:"labels,omitempty"`
}

// RegisterResponse is returned by the controller after registration.
type RegisterResponse struct {
	Accepted        bool            `json:"accepted"`
	Message         string          `json:"message"`
	InitialManifest *DeviceManifest `json:"initialManifest,omitempty"`
}

// HeartbeatRequest is sent periodically by the agent.
type HeartbeatRequest struct {
	DeviceID     string        `json:"deviceId"`
	AgentVersion string        `json:"agentVersion"`
	Status       *DeviceStatus `json:"status,omitempty"`
	Timestamp    time.Time     `json:"timestamp"`
}

// HeartbeatResponse may contain manifest updates or pending commands.
type HeartbeatResponse struct {
	ManifestUpdated bool            `json:"manifestUpdated"`
	Manifest        *DeviceManifest `json:"manifest,omitempty"`
	PendingCommands []Command       `json:"pendingCommands,omitempty"`
}

// ReportStateRequest reports the current observed state to the controller.
type ReportStateRequest struct {
	DeviceID     string        `json:"deviceId"`
	Status       *DeviceStatus `json:"status,omitempty"`
	PluginStates []PluginState `json:"pluginStates,omitempty"`
	Timestamp    time.Time     `json:"timestamp"`
}

// ReportStateResponse acknowledges the state report.
type ReportStateResponse struct {
	Acknowledged bool `json:"acknowledged"`
}

// DesiredStateResponse wraps a manifest with its version.
type DesiredStateResponse struct {
	Manifest *DeviceManifest `json:"manifest,omitempty"`
	Version  int64           `json:"version"`
}

// TelemetryMessage is the MQTT telemetry payload.
type TelemetryMessage struct {
	DeviceID  string        `json:"deviceId"`
	Timestamp time.Time     `json:"timestamp"`
	Status    *DeviceStatus `json:"status"`
}

// CommandMessage is the MQTT command payload.
type CommandMessage struct {
	Command Command `json:"command"`
}
