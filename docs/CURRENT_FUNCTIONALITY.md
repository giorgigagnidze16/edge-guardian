# EdgeGuardian — Current Functionality

> Last updated: 2026-03-01 · Phase 2 complete · Agent v0.2.0

---

## Architecture Overview

EdgeGuardian is a Kubernetes-style IoT device management system with three components:

| Component | Tech | Status |
|-----------|------|--------|
| **Go Agent** | Go 1.24, BoltDB, MQTT, zap | Fully implemented (Phase 2) |
| **Spring Boot Controller** | Java 21, Spring Boot 3.4, WebFlux, PostgreSQL 16, Flyway, MQTT 5 | Fully implemented (Phase 2) |
| **Next.js Dashboard** | Next.js 15, React 19, TypeScript, shadcn/ui | Scaffolded (Phase 5) |

gRPC was evaluated and removed in favor of HTTP/JSON to keep the agent binary under 5 MB (see `docs/ADR-001`).

---

## Go Agent

### Binaries

| Binary | Purpose | Size (linux/arm64, UPX) |
|--------|---------|------------------------|
| `edgeguardian-agent` | Main agent daemon | ~2.6 MB |
| `edgeguardian-watchdog` | Supervisor / auto-restarter | ~689 KB |

### Startup Sequence

1. Parse `--config <path>` flag (platform-specific default)
2. Load YAML config, initialize zap JSON logger
3. Create data directory, open BoltDB at `<data_dir>/agent.db`
4. Load cached desired state from BoltDB (offline-first)
5. Register with controller via HTTP (15 s timeout, non-fatal on failure)
6. Connect to MQTT broker (10 s timeout, non-fatal on failure)
7. Start four concurrent loops:
   - **Reconciler** — applies desired state at configurable interval (default 30 s)
   - **Heartbeat** — POST to controller every 30 s, receives manifest updates
   - **MQTT telemetry** — publishes device status every 30 s
   - **Signal handler** — waits for shutdown signal, cancels context

### Configuration (`agent.yaml`)

```yaml
device_id: rpi-gateway-01           # defaults to hostname
controller_address: 192.168.1.100   # default: localhost
controller_port: 8443               # default: 8443
labels:
  role: gateway
  location: office
reconcile_interval_seconds: 30
log_level: info                     # debug | info | warn | error
data_dir: /var/lib/edgeguardian     # platform-specific default

mqtt:
  broker_url: tcp://localhost:1883
  username: ""
  password: ""
  topic_root: edgeguardian

health:
  disk_path: /                      # "/" on Linux/macOS, "C:\" on Windows
```

**Platform defaults:**

| Setting | Linux | macOS | Windows |
|---------|-------|-------|---------|
| Config path | `/etc/edgeguardian/agent.yaml` | `/etc/edgeguardian/agent.yaml` | `C:\ProgramData\EdgeGuardian\agent.yaml` |
| Data dir | `/var/lib/edgeguardian` | `/var/lib/edgeguardian` | `C:\ProgramData\EdgeGuardian\data` |
| Disk path | `/` | `/` | `C:\` |
| Agent binary | `/usr/local/bin/edgeguardian-agent` | `/usr/local/bin/edgeguardian-agent` | `C:\Program Files\EdgeGuardian\edgeguardian-agent.exe` |

### Cross-Platform Support

Full build-tag separation (`_linux.go`, `_windows.go`, `_darwin.go`, `_fallback.go`). No cgo required.

| Capability | Linux | Windows | macOS | Other (FreeBSD, etc.) |
|------------|-------|---------|-------|-----------------------|
| CPU usage | `/proc/stat` delta | `GetSystemTimes` Win32 | `ps -A -o %cpu` | stub (0%) |
| Memory | `/proc/meminfo` | `GlobalMemoryStatusEx` | `sysctl hw.memsize` + `vm_stat` | Go runtime only |
| Disk usage | `statfs` (configurable path) | `GetDiskFreeSpaceExW` (configurable drive) | `unix.Statfs` (configurable path) | stub |
| Temperature | `/sys/class/thermal` or `hwmon` | no-op (needs WMI) | no-op (needs IOKit + cgo) | stub |
| Uptime | `/proc/uptime` | `GetTickCount64` | `sysctl kern.boottime` | stub |
| Service mgmt | `systemctl` | Windows SCM API | `launchctl` (auto-detects system/gui domain) | returns "unsupported" errors |
| File ownership | `syscall.Stat_t` + user lookup | no-op (POSIX N/A) | `syscall.Stat_t` + user lookup | no-op |
| Signal handling | SIGINT + SIGTERM | os.Interrupt | SIGINT + SIGTERM | SIGINT + SIGTERM |

**Degraded state thresholds** (shared across all platforms):
- Temperature > 80 °C
- Memory usage > 95%
- Disk usage > 95%

### Reconciliation Engine

Periodic loop (default 30 s) that converges actual device state toward the desired manifest.

**Flow:**
1. Diff the `DeviceManifest` into `ResourceSpec` lists (files, services)
2. Route each spec to the plugin that handles its `kind`
3. Plugin compares actual vs desired, applies changes, returns `Action`
4. Status set to `"converged"` (all noop), `"error"` (any action failed), or `"drifted"`

**Thread-safe state:** `sync.RWMutex` protects desired state, status, and action history.

### Reconciler Plugins

#### File Manager (`kind: "file"`)

Manages configuration files on disk.

| Spec Field | Example | Description |
|------------|---------|-------------|
| `path` | `/etc/app/config.yaml` | Target file path |
| `content` | `key: value\n` | Desired file content |
| `mode` | `"0644"` | Octal permission string |
| `owner` | `"root:root"` | `user:group` (Linux/macOS only) |

**Features:**
- Atomic writes (temp file → fsync → rename) to prevent corruption on power loss
- Auto-creates parent directories
- Compares content, mode, and ownership before acting
- Ownership failure is non-fatal (logs warning, reports success)

**Actions:** `create` (new file), `update` (changed content/mode/owner), `noop` (already correct), `skipped` (error or context cancelled)

#### Service Manager (`kind: "service"`)

Manages system services via the platform-native init system.

| Spec Field | Example | Description |
|------------|---------|-------------|
| `name` | `nginx` | Service/unit name |
| `state` | `"running"` or `"stopped"` | Desired running state |
| `enabled` | `"true"` or `"false"` | Enabled at boot |

**Platform behavior:**
- **Linux:** `systemctl start/stop/enable/disable`. Non-zero exit for `inactive`/`disabled` is not treated as an error.
- **macOS:** `launchctl kickstart/kill/enable/disable` (modern API) with fallback to legacy `start`/`stop`. Auto-detects `system` vs `gui/<uid>` domain based on current user.
- **Windows:** SCM API via `golang.org/x/sys/windows/svc/mgr`. Stop waits up to 10 s with context-aware polling. Clear error messages for privilege failures.

### Offline-First Persistence (BoltDB)

Three buckets:

| Bucket | Key | Value | Purpose |
|--------|-----|-------|---------|
| `desired_state` | `"current"` | JSON manifest | Survive agent restarts without controller |
| `offline_queue` | Zero-padded sequence number | MQTT message payload | Buffer telemetry when broker unreachable |
| `agent_meta` | Arbitrary string | String value | Agent metadata (e.g. version) |

**Offline queue behavior:**
- Messages enqueued with topic, payload, and timestamp
- On MQTT reconnection, drained in FIFO order (batches of 10)
- Re-queued if drain publish fails
- `QueueDepth()` exposed for monitoring

### HTTP Controller Client

Base URL: `http://<address>:<port>`, 15 s timeout per request.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/agent/register` | Send device_id, hostname, arch, os, version, labels |
| POST | `/api/v1/agent/heartbeat` | Send status metrics, receive manifest updates |
| GET | `/api/v1/agent/desired-state/{deviceId}` | Fetch manifest on demand |
| POST | `/api/v1/agent/report-state` | Push observed state |

**Retry logic:** Up to 3 attempts with exponential backoff (1 s → 2 s → 4 s). Retries on network errors and 5xx. No retry on 4xx.

### MQTT Client

Eclipse Paho v3 (`github.com/eclipse/paho.mqtt.golang`).

| Topic Pattern | Direction | QoS | Description |
|---------------|-----------|-----|-------------|
| `{root}/device/{id}/telemetry` | Agent → Broker | 1 | Health metrics + reconcile status |
| `{root}/device/{id}/command` | Broker → Agent | 1 | Commands from controller |

**Features:**
- Persistent session (`CleanSession=false`)
- Auto-reconnect (max 30 s interval)
- Keep-alive: 60 s
- Offline queue: messages persisted to BoltDB when disconnected, drained on reconnect
- Command handler registered via callback (currently logs; dispatch planned Phase 3+)

### Watchdog

Minimal supervisor for the agent process.

- Restarts agent on crash with exponential backoff (1 s → 5 min cap)
- Resets backoff on clean exit
- Platform-aware binary and config paths
- Full implementation deferred to Phase 4 (health checks, resource limits)

---

## Spring Boot Controller

### REST API

#### Agent Endpoints (`/api/v1/agent`)

| Method | Endpoint | Request | Response |
|--------|----------|---------|----------|
| POST | `/register` | `{deviceId, hostname, architecture, os, agentVersion, labels}` | `{accepted, message, initialManifest?}` |
| POST | `/heartbeat` | `{deviceId, agentVersion, status, timestamp}` | `{manifestUpdated, manifest?, pendingCommands[]}` |
| GET | `/desired-state/{deviceId}` | — | `{manifest?, version}` |
| POST | `/report-state` | `{deviceId, status}` | `{acknowledged}` |

- Registration is idempotent (upsert). Returns initial manifest if one exists.
- Heartbeat returns 404 if device not registered (triggers re-registration).
- Heartbeat always includes manifest if one exists; agent compares versions locally.
- `pendingCommands` is always empty in Phase 2 (populated in Phase 3+).

#### Dashboard Endpoints (`/api/v1/devices`)

| Method | Endpoint | Response |
|--------|----------|----------|
| GET | `/` | `Flux<DeviceDto>` — all registered devices |
| GET | `/{deviceId}` | `Mono<DeviceDto>` — single device (404 if not found) |
| DELETE | `/{deviceId}` | 204 No Content — removes device + manifest |
| GET | `/count` | `Mono<Long>` — total device count |

**DeviceDto fields:** `deviceId`, `hostname`, `architecture`, `os`, `agentVersion`, `labels`, `state`, `registeredAt`, `lastHeartbeat`, `status` (nested: cpu, memory, disk, temp, uptime, reconcileStatus)

### Database Schema (PostgreSQL 16, Flyway-managed)

#### `devices` table

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGSERIAL | PK |
| `device_id` | VARCHAR(255) | UNIQUE, NOT NULL |
| `hostname` | VARCHAR(255) | |
| `architecture` | VARCHAR(64) | e.g. `arm64` |
| `os` | VARCHAR(64) | e.g. `linux` |
| `agent_version` | VARCHAR(64) | |
| `state` | VARCHAR(32) | `ONLINE` / `DEGRADED` / `OFFLINE` |
| `labels` | JSONB | Default `{}` |
| `registered_at` | TIMESTAMPTZ | Set on first registration |
| `last_heartbeat` | TIMESTAMPTZ | Updated every heartbeat |
| `cpu_usage_percent` | DOUBLE PRECISION | |
| `memory_used_bytes` | BIGINT | |
| `memory_total_bytes` | BIGINT | |
| `disk_used_bytes` | BIGINT | |
| `disk_total_bytes` | BIGINT | |
| `temperature_celsius` | DOUBLE PRECISION | |
| `uptime_seconds` | BIGINT | |
| `last_reconcile` | TIMESTAMPTZ | |
| `reconcile_status` | VARCHAR(32) | Default `converged` |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

**Indexes:** `idx_devices_state` (B-tree on `state`), `idx_devices_labels` (GIN on `labels` JSONB)

#### `device_manifests` table

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGSERIAL | PK |
| `device_id` | VARCHAR(255) | UNIQUE, FK → `devices.device_id` ON DELETE CASCADE |
| `api_version` | VARCHAR(64) | Default `edgeguardian/v1` |
| `kind` | VARCHAR(64) | Default `DeviceManifest` |
| `metadata` | JSONB | Default `{}` |
| `spec` | JSONB | Files, services, health checks |
| `version` | BIGINT | Default 1, auto-incremented on update |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

**Index:** `idx_device_manifests_device_id` (B-tree)

### MQTT Integration

| Component | Description |
|-----------|-------------|
| `TelemetryListener` | Subscribes to `{root}/device/+/telemetry` (wildcard). Parses JSON, extracts status, calls `registry.heartbeat()`. |
| `CommandPublisher` | Publishes to `{root}/device/{id}/command` (QoS 1). Payload: `{command: {id, type, params, createdAt}}`. No-ops if MQTT not connected. |

**Command types** (defined, not yet dispatched): `ota_update`, `restart`, `exec`, `vpn_configure`

### CORS

All `/api/**` endpoints allow `http://localhost:3000` with full CRUD methods and credentials.

### Controller Configuration (`application.yaml`)

| Setting | Default |
|---------|---------|
| Server port | `8443` |
| PostgreSQL URL | `jdbc:postgresql://localhost:5432/edgeguardian` |
| DB user / password | `edgeguardian` / `edgeguardian` |
| Hibernate DDL | `validate` (Flyway manages schema) |
| MQTT broker | `tcp://localhost:1883` |
| MQTT client ID | `edgeguardian-controller` |
| MQTT topic root | `edgeguardian` |

---

## Data Model (Agent ↔ Controller Contract)

### Device Manifest (Desired State)

```yaml
apiVersion: edgeguardian/v1
kind: DeviceManifest
metadata:
  name: rpi-gateway-01
  labels: { role: gateway }
  annotations: {}
spec:
  files:
    - path: /etc/app/config.yaml
      content: "key: value\n"
      mode: "0644"
      owner: "root:root"
  services:
    - name: nginx
      enabled: "true"
      state: running
  healthChecks:
    intervalSeconds: 60
    checks:
      - name: disk-usage
        type: exec
        target: "df -h / | awk 'NR==2 {print $5}'"
        timeoutSeconds: 5
version: 1
```

### Device Status (Reported State)

```json
{
  "state": "online",
  "cpuUsagePercent": 23.5,
  "memoryUsedBytes": 536870912,
  "memoryTotalBytes": 1073741824,
  "diskUsedBytes": 5368709120,
  "diskTotalBytes": 32212254720,
  "temperatureCelsius": 52.3,
  "uptimeSeconds": 86400,
  "lastReconcile": "2026-03-01T12:00:00Z",
  "reconcileStatus": "converged"
}
```

### MQTT Telemetry Message

```json
{
  "deviceId": "rpi-gateway-01",
  "timestamp": "2026-03-01T12:00:00Z",
  "status": { /* DeviceStatus fields */ }
}
```

### MQTT Command Message

```json
{
  "command": {
    "id": "uuid",
    "type": "restart",
    "params": {},
    "createdAt": "2026-03-01T12:00:00Z"
  }
}
```

---

## Test Coverage

### Agent (Go) — 80 unit + 32 integration = 112 tests

**Unit tests** (run on any OS with `go test ./...`):

| Package | Tests | What's Covered |
|---------|-------|----------------|
| `internal/config` | 5 | Default values, YAML loading (valid, invalid, partial, missing file) |
| `internal/health` | 10 | Constructor, `computeState` thresholds (healthy, high memory/disk/temp), `FormatMetrics`, `Collect` on host OS, CPU delta |
| `internal/comms` | 13 | HTTP client via `httptest.NewServer`: register (success/conflict/retry/bad URL), heartbeat (success/401), desired-state (success/404), report-state, content-type, context cancellation, close |
| `internal/model` | 6 | JSON round-trip for DeviceStatus, DeviceManifest, TelemetryMessage, Command; state string values; empty resources |
| `internal/reconciler` | 18 | Diff (nil/empty/files/services/kind routing/flat list) + orchestration (no desired state, empty manifest, dispatch, plugin errors, context cancellation, concurrency, large manifest, status tracking) |
| `internal/storage` | 10 | BoltDB save/load/overwrite, offline queue FIFO, metadata, empty cases |
| `plugins/filemanager` | 9 | Create, update, noop, parent dirs, empty path, context cancellation, multiple files, name, can-handle |
| `plugins/service` | 9 | Start, stop, enable, noop, failure, empty name, multiple changes, name, can-handle (all via mock executor) |

**Integration tests** (require Docker — run via `Dockerfile.test`):

| Package | Tests | What's Covered |
|---------|-------|----------------|
| `internal/comms` (MQTT) | 10 | Real Mosquitto broker: connect/disconnect, publish telemetry, subscribe commands, offline queue, pub/sub E2E, telemetry loop, drain offline queue, topic format |
| `internal/health` (Linux) | 9 | Real `/proc/stat`, `/proc/meminfo`, `/proc/uptime`; CPU/memory/disk/uptime collection; custom disk path; end-to-end state computation |
| `plugins/filemanager` (Linux) | 7 | Real `chmod` (0644/0755/0600), mode change on existing file, `chown` with current user, atomic write verification, nested directory creation |
| `plugins/service` (systemd) | 6 | Real `systemctl`: start, stop, enable, noop when converged, drift detection + correction, disable + stop |

Integration tests run inside a systemd-enabled Ubuntu 22.04 container with Mosquitto:
```
docker build -f Dockerfile.test -t eg-agent-test .
docker run --rm --privileged eg-agent-test /run-tests.sh
```

### Controller (Java) — 17 tests

| Class | Tests | What's Covered |
|-------|-------|----------------|
| `AgentApiControllerTest` | 7 | Register (success, blank ID, with manifest), heartbeat (success, unknown device), desired-state (with/without manifest), report-state |
| `DeviceRegistryTest` | 10 | Register new/existing/with-labels, heartbeat status update, find/remove/count, manifest save/update/version increment |

All controller integration tests run against real PostgreSQL 16 via Testcontainers.

---

## Deployment Artifacts

### Docker Compose (`deployments/docker-compose.yml`)

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| `postgres` | `postgres:16-alpine` | 5432 | Device and manifest storage |
| `mosquitto` | `eclipse-mosquitto:2` | 1883, 9001 | MQTT broker (plain + WebSocket) |

### Systemd Unit (`deployments/systemd/edgeguardian-agent.service`)

Hardened unit file: `ProtectSystem=strict`, `NoNewPrivileges=true`, `LimitNOFILE=65536`, runs as `edgeguardian` user, restart on failure with 5 s delay.

### Build Script (`scripts/build-agent.sh`)

Cross-compilation with `CGO_ENABLED=0`. UPX compression if available. **Enforces 5 MB size gate** (fails build if agent binary exceeds 5 MB).

---

## Architecture Decisions

| ADR | Decision | Rationale |
|-----|----------|-----------|
| ADR-001 | HTTP/JSON over gRPC | gRPC adds 6.7 MB to binary (11 MB vs 4.3 MB). HTTP/JSON uses stdlib at zero cost. |
| ADR-002 | UPX binary compression | Reduces 7.2 MB → 2.6 MB (65%). 50-200 ms startup overhead on RPi. |

---

## Not Yet Implemented

| Feature | Planned Phase |
|---------|---------------|
| Embedded CA + mTLS enrollment | Phase 3 |
| OTA update pipeline (Ed25519 signed) | Phase 3 |
| Agent certificate renewal | Phase 3 |
| WireGuard VPN integration | Phase 4 |
| Health check probes (HTTP/TCP/exec) | Phase 4 |
| Full watchdog (resource limits, health gating) | Phase 4 |
| Next.js dashboard (7 pages) | Phase 5 |
| Fleet simulation (10-50 agents) | Phase 5 |
| Fleet simulation benchmarks | Phase 5 |
| GPIO plugin | Phase 6 (optional) |
| RBAC | Phase 6 (optional) |
| MQTT command dispatch in agent | Phase 3+ |
| CI/CD pipeline | Not scheduled |
