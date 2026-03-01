# EdgeGuardian — Current Functionality


---

## Architecture Overview

EdgeGuardian is a Kubernetes-style IoT device management system with three components:

| Component | Tech | Status |
|-----------|------|--------|
| **Go Agent** | Go 1.24, BoltDB, MQTT, zap | Fully implemented (Phases 1-3) |
| **Spring Boot Controller** | Java 21, Spring Boot 3.4, PostgreSQL 16, Flyway, MQTT 5, Keycloak OIDC | Fully implemented (Phases 1-3) |
| **Next.js Dashboard** | Next.js 15, React 19, TypeScript, shadcn/ui, TanStack Query, Recharts, Monaco | Fully implemented (Phase 5) |

gRPC was evaluated and removed in favor of HTTP/JSON to keep the agent binary under 5 MB (see `docs/ADR-001`).

---

## Implementation Progress

### Phase 1: Foundation (Weeks 1-4) — COMPLETE

| Deliverable | Status | Notes |
|-------------|--------|-------|
| Go agent scaffold (cmd/agent) | Done | Entry point, config loading, signal handler |
| HTTP agent-controller comms | Done | Register, heartbeat, desired-state, report-state |
| Spring Boot controller scaffold | Done | Spring Boot 3.4, JPA, REST controllers |
| PostgreSQL + Flyway V1 migration | Done | devices, device_manifests tables |
| YAML config parser | Done | Platform-specific defaults |
| Basic health collector | Done | CPU, RAM, disk, temperature, uptime |
| Cross-compile script | Done | `scripts/build-agent.sh` with UPX + 5MB gate |

### Phase 2: Core (Weeks 5-8) — COMPLETE

| Deliverable | Status | Notes |
|-------------|--------|-------|
| Reconciliation engine | Done | 30s loop, manifest diff, plugin dispatch |
| Plugin system (interface + dispatch) | Done | Name(), CanHandle(), Reconcile() |
| File Manager plugin | Done | Atomic writes, mode/ownership, parent dir creation |
| Service Manager plugin | Done | systemctl (Linux), launchctl (macOS), Windows SCM |
| BoltDB persistence | Done | Desired state cache, offline queue, metadata |
| MQTT client (Paho v3) | Done | Telemetry publish, command subscribe, auto-reconnect |
| MQTT integration (controller) | Done | TelemetryListener, LogIngestionListener, CommandPublisher |
| Cross-platform build tags | Done | `_linux.go`, `_windows.go`, `_darwin.go`, `_fallback.go` |
| 112 agent tests | Done | 80 unit + 32 integration (MQTT, /proc, systemd, filemanager) |
| 17+ controller tests | Done | AgentApiController, DeviceRegistry (Testcontainers PostgreSQL) |

### Phase 3: Security + OTA (Weeks 9-12) — COMPLETE

| Deliverable | Status | Notes |
|-------------|--------|-------|
| Keycloak OIDC integration | Done | Stateless JWT, Google/GitHub federated login |
| Multi-tenant organizations | Done | Roles: owner/admin/operator/viewer |
| RBAC + tenant interceptor | Done | Per-request org membership resolution |
| Enrollment tokens | Done | Single-use device enrollment |
| API key management | Done | SHA-256 hashed, one-time reveal |
| Audit trail | Done | Immutable log, user attribution |
| OTA artifact upload | Done | Ed25519 signature verification |
| OTA rolling deployments | Done | Label-based targeting, progress tracking |
| Agent OTA updater | Done | Streaming download, SHA-256 + Ed25519 verify, exit code 42 watchdog trigger |
| Command dispatcher | Done | Routes: ota_update, restart, exec, vpn_configure |
| Log forwarding (agent) | Done | Ring buffer, journalctl (Linux), file tailer |
| Log ingestion (controller → Loki) | Done | MQTT → Loki HTTP push |
| EMQX broker (replaced Mosquitto) | Done | Device-scoped ACL |
| Flyway V2 (auth) + V3 (OTA) | Done | 11 new tables |
| **NOT done**: Embedded CA + mTLS | Deferred | Planned but skipped — would add complexity without thesis value |
| **NOT done**: Agent cert enrollment | Deferred | Requires embedded CA |
| **NOT done**: Health-gated OTA rollback | Partial | Agent downloads + verifies, but no automatic health-gate rollback |

### Phase 4: VPN + Monitoring (Weeks 13-16) — NOT STARTED

| Deliverable | Status | Notes |
|-------------|--------|-------|
| WireGuard VPN integration | Not started | `agent/internal/vpn/` is empty scaffold |
| VPN group management (controller) | Not started | |
| Health check probes (HTTP/TCP/exec) | Not started | |
| Auto-restart on health failure | Not started | |
| Full watchdog implementation | Partial | Minimal supervisor done (exponential backoff, exit code 42), full version deferred |

### Phase 5: Dashboard (Weeks 17-20) — COMPLETE

| Deliverable | Status | Notes |
|-------------|--------|-------|
| Next.js 15 dashboard (11 pages) | Done | Fleet overview, devices, logs, manifest editor, OTA, settings, audit, integrations |
| Keycloak OIDC login (NextAuth v5) | Done | PKCE flow, JWT cookie, middleware protection |
| Command palette (Cmd+K) | Done | Navigation, device search, theme toggle |
| Dark/light mode ("Midnight Luminance") | Done | Cyan accent, glass morphism, Plus Jakarta Sans |
| Landing page | Done | Scroll-driven design, animated terminal demo, platform marquee |
| Responsive design | Done | Mobile sidebar, responsive grids |
| **NOT done**: Fleet simulation | Partial | `scripts/simulate-fleet.sh` exists but not battle-tested |
| **NOT done**: E2E integration tests | Not started | |
| **NOT done**: Performance benchmarks | Not started | |

### Phase 6: Buffer / Polish (Weeks 21-24) — NOT STARTED

| Deliverable | Status | Notes |
|-------------|--------|-------|
| GPIO plugin | Not started | Empty scaffold at `agent/plugins/gpio/` |
| CI/CD pipeline | Not started | |
| Android agent support | Planned | See "Android Support" section below |
| Thesis writing | Not started | |

---

## Infrastructure (Docker Compose)

All services run via `deployments/docker-compose.yml`:

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| **PostgreSQL** | postgres:16-alpine | 5432 | Device, manifest, org, OTA storage; Keycloak DB |
| **Keycloak** | quay.io/keycloak:26.0 | 9090 | OIDC provider (Google/GitHub federated login) |
| **EMQX** | emqx/emqx:5.8 | 1883, 8083, 18083 | MQTT broker (replaced Mosquitto for better ACL) |
| **Loki** | grafana/loki:3.3.0 | 3100 | Log aggregation (agent → controller → Loki) |
| **Grafana** | grafana/grafana:11.4.0 | 3000 | Visualization (Loki datasource, Keycloak SSO) |

**Credentials (dev):**
- PostgreSQL: `edgeguardian` / `edgeguardian-dev`
- Keycloak admin: `admin` / `admin`
- Grafana admin: `admin` / `admin`
- EMQX admin: `admin` / `public`

```bash
cd deployments && docker compose up -d
```

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
7. Start concurrent loops:
   - **Reconciler** — applies desired state at configurable interval (default 30 s)
   - **Heartbeat** — POST to controller every 30 s, receives manifest updates
   - **MQTT telemetry** — publishes device status every 30 s
   - **Log forwarding** — streams logs to MQTT (journalctl on Linux, file tailer elsewhere)
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

### Cross-Platform Support

Full build-tag separation (`_linux.go`, `_windows.go`, `_darwin.go`, `_fallback.go`). No cgo required.

| Capability | Linux | Windows | macOS | Android (planned) | Other |
|------------|-------|---------|-------|--------------------|-------|
| CPU usage | `/proc/stat` delta | `GetSystemTimes` Win32 | `ps -A -o %cpu` | `/proc/stat` (shared w/ Linux) | stub (0%) |
| Memory | `/proc/meminfo` | `GlobalMemoryStatusEx` | `sysctl hw.memsize` | `/proc/meminfo` (shared w/ Linux) | Go runtime only |
| Disk usage | `statfs` | `GetDiskFreeSpaceExW` | `unix.Statfs` | `statfs` (shared w/ Linux) | stub |
| Temperature | `/sys/class/thermal` | no-op | no-op | `/sys/class/thermal` (shared w/ Linux) | stub |
| Uptime | `/proc/uptime` | `GetTickCount64` | `sysctl kern.boottime` | `/proc/uptime` (shared w/ Linux) | stub |
| Service mgmt | `systemctl` | Windows SCM API | `launchctl` | Termux/init.d (planned) | unsupported |
| File ownership | `syscall.Stat_t` | no-op | `syscall.Stat_t` | `syscall.Stat_t` (shared w/ Linux) | no-op |

### Reconciliation Engine

Periodic loop (default 30 s) that converges actual device state toward the desired manifest.

1. Diff the `DeviceManifest` into `ResourceSpec` lists (files, services)
2. Route each spec to the plugin that handles its `kind`
3. Plugin compares actual vs desired, applies changes, returns `Action`
4. Status set to `"converged"`, `"error"`, or `"drifted"`

### Plugins

#### File Manager (`kind: "file"`)
- Atomic writes (temp file → fsync → rename)
- Auto-creates parent directories
- Compares content, mode, and ownership

#### Service Manager (`kind: "service"`)
- Linux: `systemctl start/stop/enable/disable`
- macOS: `launchctl` (modern API, auto-detects system/gui domain)
- Windows: SCM API via `golang.org/x/sys/windows/svc/mgr`

### OTA Pipeline (Phase 3)

- `internal/ota/updater.go` — streaming HTTP download with SHA-256 verification
- Ed25519 signature validation
- Apply: chmod + exit code 42 (signals watchdog to swap binary)

### Command Dispatcher (Phase 3)

Routes MQTT commands by type: `ota_update`, `restart`, `exec`, `vpn_configure`

### Log Forwarding (Phase 3)

- Ring buffer forwarder: collects log entries, flushes batches via MQTT
- Linux: `journalctl --follow` subprocess streaming
- Other: file tailer (polls for new lines)

### Offline-First Persistence (BoltDB)

| Bucket | Purpose |
|--------|---------|
| `desired_state` | Survive restarts without controller |
| `offline_queue` | Buffer MQTT messages when broker unreachable |
| `agent_meta` | Agent metadata (version, etc.) |

### HTTP Client

Base URL: `http://<address>:<port>`, 15 s timeout, 3 retries with exponential backoff.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/agent/register` | Device registration |
| POST | `/api/v1/agent/heartbeat` | Status + receive manifest |
| GET | `/api/v1/agent/desired-state/{deviceId}` | Fetch manifest |
| POST | `/api/v1/agent/report-state` | Push observed state |
| POST | `/api/v1/agent/enroll` | Enrollment token + CSR exchange |

### MQTT Client

Eclipse Paho v3 with persistent session, auto-reconnect, offline queue.

| Topic | Direction | Description |
|-------|-----------|-------------|
| `{root}/device/{id}/telemetry` | Agent → Broker | Health metrics |
| `{root}/device/{id}/command` | Broker → Agent | Commands from controller |
| `{root}/device/{id}/status` | Agent → Broker | Reconcile status |
| `{root}/device/{id}/logs` | Agent → Broker | Device logs |

### Watchdog

Minimal supervisor: restarts on crash with exponential backoff (1 s → 5 min cap). Exit code 42 triggers binary swap. Full implementation deferred to Phase 4.

---

## Spring Boot Controller

### REST API

#### Agent Endpoints (`/api/v1/agent/`, no auth required)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/register` | Device registration (idempotent upsert) |
| POST | `/heartbeat` | Status update + manifest delivery |
| GET | `/desired-state/{deviceId}` | Fetch manifest on demand |
| POST | `/report-state` | Push observed state |
| POST | `/enroll` | Enrollment token exchange |

#### Device Management (`/api/v1/devices/`, requires Keycloak JWT)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List all devices in org |
| GET | `/{deviceId}` | Single device detail |
| DELETE | `/{deviceId}` | Remove device + manifest |
| GET | `/count` | Total device count |
| GET | `/{deviceId}/logs` | Query logs from Loki (time range, level, search) |

#### Organizations (`/api/v1/organizations/`, requires JWT)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET/POST | `/` | List/create organizations |
| GET/PUT/DELETE | `/{orgId}` | Get/update/delete org |
| GET/POST/DELETE | `/{orgId}/members` | Member CRUD with role management |
| GET/POST/DELETE | `/{orgId}/enrollment-tokens` | Enrollment token CRUD |
| GET/POST/DELETE | `/{orgId}/api-keys` | API key CRUD (one-time reveal) |

#### OTA Management (`/api/v1/organizations/{orgId}/ota/`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET/POST | `/artifacts` | List/upload OTA artifacts |
| GET/POST | `/deployments` | List/create deployments (label-based targeting) |

#### User & Audit

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/me` | User profile + org memberships |
| GET | `/organizations/{orgId}/audit` | Audit log (paginated, filterable) |

### Database Schema (PostgreSQL 16, Flyway)

| Migration | Tables |
|-----------|--------|
| V1__init | `devices`, `device_manifests` |
| V2__auth_and_tenancy | `organizations`, `users`, `organization_members`, `enrollment_tokens`, `api_keys`, `audit_log` |
| V3__ota | `ota_artifacts`, `ota_deployments`, `deployment_device_status` |

### Security

- **Stateless** (no HTTP sessions, CSRF disabled)
- **JWT validation** via Keycloak issuer
- **API Key filter** (`X-API-Key` header, SHA-256 lookup)
- **Tenant interceptor** resolves org membership per request
- Agent endpoints are public (device-scoped)

### Services

| Service | Purpose |
|---------|---------|
| DeviceRegistry | Register/heartbeat/manifest CRUD |
| OrganizationService | Org CRUD + role-based permissions |
| EnrollmentService | Token validation for device enrollment |
| ApiKeyService | Key generation (SHA-256) + revocation |
| UserService | Keycloak sync on first login, auto-creates personal org |
| AuditService | Immutable audit trail |
| OTAService | Artifact upload + label-based deployment |
| LogService | Loki HTTP client (push + LogQL query) |
| DeviceHealthScheduler | Marks devices OFFLINE after 5 min |

### MQTT Integration

- `TelemetryListener`: subscribes to `{root}/device/+/telemetry`, updates DB
- `LogIngestionListener`: subscribes to `{root}/device/+/logs`, pushes to Loki
- `CommandPublisher`: publishes commands to `{root}/device/{id}/command`

---

## Next.js Dashboard (Phase 5)

### Tech Stack

Next.js 15, React 19, TypeScript 5.7, Tailwind CSS v4, shadcn/ui (Radix), TanStack Query v5, NextAuth v5 (Keycloak OIDC), Recharts, Monaco Editor, Sonner (toasts), cmdk (command palette)

### Authentication

Keycloak OIDC via NextAuth v5 with PKCE. JWT stored in cookie with access token for API calls. Middleware protects all dashboard routes.

### Pages (11 routes)

| Route | Description |
|-------|-------------|
| `/` | Fleet overview: metric cards, fleet health chart, resource consumers, recent deployments, devices needing attention, recent activity |
| `/devices` | Device list with filters (state, architecture, labels), bulk actions, sorting, pagination |
| `/devices/[id]` | Device detail: metric cards with sparklines, resource charts, device info, labels, action menu |
| `/devices/[id]/logs` | Log viewer: Loki-backed, level filter, time range, auto-scroll, search |
| `/devices/[id]/manifest` | Monaco YAML editor with theme-aware syntax highlighting, Ctrl+S save |
| `/ota` | OTA management: artifacts table + deployments table, upload artifact dialog, create deployment wizard |
| `/ota/[deploymentId]` | Deployment detail: progress bar, state counts, per-device status table |
| `/settings` | Org settings: general info, members, enrollment tokens, API keys (all with CRUD dialogs) |
| `/audit` | Audit log: timeline with action/resource badges, user attribution, filtering |
| `/integrations` | Integration cards: REST API, CI/CD, MQTT, device enrollment |
| `/auth/login` | Landing page: scroll-driven hero, animated terminal demo, platform marquee, light/dark mode |

### UI Features

- **Dark-first "Midnight Luminance" design** with cyan (#06b6d4) accent, glass morphism, Plus Jakarta Sans + JetBrains Mono fonts
- **Command palette** (Cmd+K): navigation, device search, theme toggle
- **Collapsible sidebar** with org switcher, dark mode toggle, user info
- **Toast notifications** on all mutations
- **Error boundaries** with retry
- **Responsive** (mobile sidebar via Sheet, responsive grids)
- **Performance optimized**: Turbopack, lazy-loaded recharts/command palette/dialogs, `optimizePackageImports`

---

## Supported Platforms

### Architectures

| Architecture | Agent Binary | Status |
|--------------|-------------|--------|
| ARM64 (aarch64) | `linux/arm64` | Supported |
| ARMv7 (armhf) | `linux/arm` | Supported |
| x86_64 (amd64) | `linux/amd64` | Supported |
| RISC-V (riscv64) | `linux/riscv64` | Planned (Go supports it, untested) |

### Operating Systems & Devices

| OS / Platform | Status | Notes |
|---------------|--------|-------|
| Linux (generic) | Supported | Primary target. Full `/proc` + systemd integration |
| Raspberry Pi (all models) | Supported | ARM64/ARMv7, systemd, `/sys/class/thermal` |
| Ubuntu / Debian | Supported | systemd service management |
| Alpine Linux | Supported | Lightweight containers, OpenRC via fallback |
| NVIDIA Jetson | Supported | ARM64 Linux, full agent features |
| Intel NUC | Supported | x86_64 Linux |
| ESP32 (via Termux/Linux) | Experimental | Limited — ESP-IDF native agent not planned |
| Windows | Supported | SCM service management, Win32 health APIs |
| macOS | Supported | launchctl, sysctl health APIs |
| **Android** | **Planned** | See "Android Support" section below |

---

## Android Support (Planned)

### Overview

Android devices run the Linux kernel, so the Go agent can cross-compile to `linux/arm64` or `linux/arm` and run on Android. The primary deployment targets are:

- **Rooted Android devices** — agent runs as a system service
- **Termux** — agent runs in userland without root
- **Android Things / dedicated IoT boards** — full Linux-like environment

### Implementation Plan

| Component | Approach | Complexity |
|-----------|----------|------------|
| **Agent binary** | Cross-compile `GOOS=linux GOARCH=arm64` — already works | None (already supported) |
| **Health metrics** | `/proc/stat`, `/proc/meminfo` — shared with Linux build tags | None (already works) |
| **Disk usage** | `statfs` on `/data` or `/sdcard` instead of `/` | Low — config change |
| **Temperature** | `/sys/class/thermal` — same as Linux | None (already works) |
| **Service management** | New `_android.go` build tag: `am start/stop` for apps, `init.d` scripts for Termux | Medium |
| **File management** | Works as-is (atomic writes, mode management) | None |
| **OTA updates** | APK sideload for Android apps, binary swap for Termux | Medium |
| **Log forwarding** | `logcat` reader (replaces `journalctl`) | Low — new log source |
| **Data directory** | `/data/local/tmp/edgeguardian` (rooted) or `$HOME/.edgeguardian` (Termux) | Low — config default |
| **Connectivity** | HTTP + MQTT work over Android's network stack | None |

### What's Needed

1. **`_android.go` build tags** for service management and log reading
2. **`logcat` log source** as alternative to `journalctl`
3. **Android-specific default paths** in config
4. **Build script addition**: `GOOS=linux GOARCH=arm64` target for Android
5. **Dashboard**: add "android" as a recognized OS in device display

### What Already Works (Zero Changes)

- Agent binary compiles and runs on Android (Termux confirmed for `GOOS=linux GOARCH=arm64`)
- Health metrics via `/proc` (Android exposes same Linux procfs)
- Temperature via `/sys/class/thermal`
- HTTP + MQTT communication
- BoltDB persistence
- File manager plugin
- OTA download + verification
- Reconciliation loop

---

## Data Model

### Device Manifest (Desired State)

```yaml
apiVersion: edgeguardian/v1
kind: DeviceManifest
metadata:
  name: rpi-gateway-01
  labels: { role: gateway }
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
  "reconcileStatus": "converged"
}
```

---

## Test Coverage

### Agent (Go) — 112 tests

| Category | Tests | Scope |
|----------|-------|-------|
| Unit | 80 | config, health, HTTP comms, model, reconciler, storage, filemanager, service |
| Integration | 32 | MQTT (real Mosquitto), Linux health (/proc), filemanager (chmod/chown), systemd |

### Controller (Java) — 19+ tests

| Class | Tests | Scope |
|-------|-------|-------|
| AgentApiControllerTest | 7 | Registration, heartbeat, desired-state, report-state |
| DeviceRegistryTest | 10+ | CRUD, labels, manifest versioning (real PostgreSQL via Testcontainers) |

---

## Architecture Decisions

| ADR | Decision | Rationale |
|-----|----------|-----------|
| ADR-001 | HTTP/JSON over gRPC | gRPC adds 6.7 MB to binary (11 MB vs 4.3 MB) |
| ADR-002 | UPX binary compression | 7.2 MB → 2.6 MB (65% reduction), 50-200 ms startup overhead |

---

## Quick Start (Local Development)

```bash
# 1. Infrastructure (PostgreSQL, Keycloak, EMQX, Loki, Grafana)
cd deployments && docker compose up -d
# Wait 30-60s for all services to be healthy

# 2. Controller (Java 21 required)
cd controller && ./gradlew bootRun
# Runs on port 8443, Flyway auto-migrates

# 3. Dashboard
cd ui && pnpm install && pnpm dev
# Runs on port 3001 (3000 is Grafana)
# Open http://localhost:3001 → redirects to Keycloak login

# 4. Agent (optional, for live device data)
cd agent && go build -o edgeguardian-agent ./cmd/agent/
./edgeguardian-agent --config ../configs/sample-agent-config.yaml
```

### Service URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Dashboard | http://localhost:3001 | Keycloak login |
| Keycloak Admin | http://localhost:9090 | admin / admin |
| Grafana | http://localhost:3000 | admin / admin |
| EMQX Dashboard | http://localhost:18083 | admin / public |
| Controller API | http://localhost:8443 | Bearer JWT |
