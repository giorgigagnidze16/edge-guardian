// Package model defines the core data types for EdgeGuardian agent.
// These are plain Go structs with JSON tags that serve as the contract
// between the agent and controller over MQTT/JSON.
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
	Type      string            `json:"type"` // "ota_update", "restart", "script", or custom
	Params    map[string]string `json:"params,omitempty"`
	Script    *ScriptSpec       `json:"script,omitempty"`
	Hooks     *CommandHooks     `json:"hooks,omitempty"`
	Timeout   int               `json:"timeout,omitempty"` // seconds, 0 = no timeout
	CreatedAt time.Time         `json:"createdAt"`
}

// ScriptSpec defines an inline script to execute on the device.
type ScriptSpec struct {
	Inline      string            `json:"inline,omitempty"`
	Interpreter string            `json:"interpreter,omitempty"` // "bash", "sh", "python3", "powershell"
	WorkDir     string            `json:"workDir,omitempty"`
	Env         map[string]string `json:"env,omitempty"`
	RunAs       string            `json:"runAs,omitempty"`
	Timeout     int               `json:"timeout,omitempty"` // seconds
}

// CommandHooks defines pre/post lifecycle hooks for a command.
type CommandHooks struct {
	Pre  *ScriptSpec `json:"pre,omitempty"`
	Post *ScriptSpec `json:"post,omitempty"`
}

// CommandResult reports the outcome of a command execution back to the controller.
type CommandResult struct {
	CommandID    string    `json:"commandId"`
	DeviceID     string    `json:"deviceId"`
	Phase        string    `json:"phase"`  // "pre_hook", "main", "post_hook"
	Status       string    `json:"status"` // "success", "failed", "running", "timeout"
	ExitCode     int       `json:"exitCode"`
	Stdout       string    `json:"stdout,omitempty"`
	Stderr       string    `json:"stderr,omitempty"`
	ErrorMessage string    `json:"errorMessage,omitempty"`
	DurationMs   int64     `json:"durationMs"`
	Timestamp    time.Time `json:"timestamp"`
}

// RegisterResponse is returned by the controller after enrollment.
type RegisterResponse struct {
	Accepted        bool            `json:"accepted"`
	Message         string          `json:"message"`
	InitialManifest *DeviceManifest `json:"initialManifest,omitempty"`
	DeviceToken     string          `json:"deviceToken,omitempty"`
}

// HeartbeatMessage is published via MQTT to report agent liveness and status.
type HeartbeatMessage struct {
	DeviceID        string        `json:"deviceId"`
	AgentVersion    string        `json:"agentVersion"`
	Status          *DeviceStatus `json:"status,omitempty"`
	ManifestVersion int64         `json:"manifestVersion"`
	Timestamp       time.Time     `json:"timestamp"`
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

// EnrollRequest is sent by the agent to enroll with an enrollment token.
type EnrollRequest struct {
	EnrollmentToken string            `json:"enrollmentToken"`
	DeviceID        string            `json:"deviceId"`
	Hostname        string            `json:"hostname"`
	Architecture    string            `json:"architecture"`
	OS              string            `json:"os"`
	AgentVersion    string            `json:"agentVersion"`
	Labels          map[string]string `json:"labels,omitempty"`
}

// OTAStatus is included in heartbeat to report OTA progress.
type OTAStatus struct {
	DeploymentID int64  `json:"deploymentId,omitempty"`
	State        string `json:"state,omitempty"`    // downloading, verifying, applying, completed, failed
	Progress     int    `json:"progress,omitempty"` // 0-100
}

// OTAStatusReport is sent to the controller to report OTA update progress.
type OTAStatusReport struct {
	DeploymentID int64  `json:"deploymentId"`
	DeviceID     string `json:"deviceId"`
	State        string `json:"state"`
	Progress     int    `json:"progress"`
	ErrorMessage string `json:"errorMessage,omitempty"`
}
