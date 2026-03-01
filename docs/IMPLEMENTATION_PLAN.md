# EdgeGuardian Implementation Plan

> Last updated: 2026-03-01

---

## Progress Summary

| Phase | Weeks | Status | Completion |
|-------|-------|--------|------------|
| **Phase 1** — Foundation | 1-4 | **COMPLETE** | 100% |
| **Phase 2** — Core | 5-8 | **COMPLETE** | 100% |
| **Phase 3** — Security + OTA | 9-12 | **COMPLETE** (partial) | ~80% — Keycloak, RBAC, OTA, Loki done. Embedded CA + mTLS deferred. |
| **Phase 4** — VPN + Monitoring | 13-16 | **NOT STARTED** | 0% — Empty scaffolds only |
| **Phase 5** — Dashboard | 17-20 | **COMPLETE** | ~85% — 11-page SaaS UI done. Fleet sim, E2E tests, benchmarks incomplete. |
| **Phase 6** — Buffer / Polish | 21-24 | **NOT STARTED** | 0% |

---

## Phase 1: Foundation (Weeks 1-4) — COMPLETE

### Go Agent
- `cmd/agent/main.go` — Entry point: config, BoltDB, HTTP client, health, shutdown
- `cmd/watchdog/main.go` — Minimal supervisor with exponential backoff
- `internal/config/config.go` — YAML config with MQTT, controller, health sections
- `internal/model/manifest.go` — All data types: manifests, status, DTOs
- `internal/comms/http_client.go` — HTTP/JSON controller client with retry
- `internal/health/collector.go` — CPU, RAM, disk, temperature, uptime

### Spring Boot Controller
- `EdgeGuardianControllerApplication.java` — Spring Boot 3.4.3 entry point
- `model/Device.java` — JPA entity: deviceId, hostname, arch, os, labels (JSONB), state
- `model/DeviceManifestEntity.java` — JPA entity: manifest with versioning
- `api/AgentApiController.java` — Agent REST: register, heartbeat, desired-state
- `api/DeviceController.java` — Dashboard REST: GET/DELETE devices
- `service/DeviceRegistry.java` — JPA-backed device CRUD

### Infrastructure
- `deployments/docker-compose.yml` — PostgreSQL 16
- `scripts/build-agent.sh` — Cross-compile, UPX, 5MB gate
- `configs/sample-agent-config.yaml` + `sample-device-manifest.yaml`
- `docs/ADR-001-agent-http-over-grpc.md`
- `docs/ADR-002-upx-binary-compression.md`

**Binary size** (linux/arm64, stripped + UPX): **2.6 MB** (target: <5 MB)

---

## Phase 2: Core (Weeks 5-8) — COMPLETE

### Go Agent Additions
- `internal/reconciler/` — 30s reconciliation loop, manifest diff, plugin dispatch
- `internal/storage/store.go` — BoltDB: desired state, offline queue, metadata
- `internal/comms/mqtt_client.go` — Paho v3: telemetry publish, command subscribe, offline queue
- `plugins/filemanager/` — Atomic file writes, mode/ownership, parent dirs
- `plugins/service/` — systemctl (Linux), launchctl (macOS), Windows SCM
- Cross-platform build tags: `_linux.go`, `_windows.go`, `_darwin.go`, `_fallback.go`

### Controller Additions
- `config/MqttConfig.java` — Eclipse Paho MQTT v5 client bean
- `mqtt/TelemetryListener.java` — Subscribes to device telemetry, updates DB
- `mqtt/CommandPublisher.java` — Publishes commands to devices
- Flyway V1 migration: `devices`, `device_manifests`

### Tests
- 112 agent tests (80 unit + 32 integration)
- 17+ controller tests (real PostgreSQL via Testcontainers)

---

## Phase 3: Security + OTA (Weeks 9-12) — COMPLETE (partial)

### What Was Implemented

#### Authentication & Authorization (controller)
- `security/SecurityConfig.java` — Stateless JWT validation via Keycloak
- `security/ApiKeyAuthenticationFilter.java` — SHA-256 hashed API key lookup
- `security/TenantContext.java` + `config/TenantInterceptor.java` — Per-request org resolution
- `model/` — Organization, User, OrganizationMember, EnrollmentToken, ApiKey, AuditLog entities
- `service/` — OrganizationService, UserService, EnrollmentService, ApiKeyService, AuditService
- `api/` — OrganizationController, EnrollmentTokenController, ApiKeyController
- Flyway V2: organizations, users, org_members, enrollment_tokens, api_keys, audit_log

#### OTA Pipeline (controller + agent)
- `service/OTAService.java` — Artifact upload (Ed25519 signature), label-based rolling deployments
- `api/OTAController.java` — REST: artifacts, deployments
- `model/` — OtaArtifact, OtaDeployment, DeploymentDeviceStatus
- Flyway V3: ota_artifacts, ota_deployments, deployment_device_status
- `agent/internal/ota/updater.go` — Streaming HTTP download, SHA-256 + Ed25519 verification
- `agent/internal/commands/dispatcher.go` — Routes: ota_update, restart, exec, vpn_configure

#### Observability
- `agent/internal/logfwd/` — Ring buffer forwarder, journalctl reader, file tailer
- `mqtt/LogIngestionListener.java` — MQTT → Loki HTTP push
- `service/LogService.java` — Loki HTTP client (push + LogQL query)

#### Infrastructure
- Keycloak realm config (`edgeguardian-realm.json`) with Google/GitHub IdP
- EMQX broker with device-scoped ACL (replaced Mosquitto)
- Loki + Grafana for log aggregation/visualization

### What Was Deferred

| Feature | Reason |
|---------|--------|
| Embedded Certificate Authority | Added complexity without thesis value; Keycloak OIDC sufficient |
| Agent mTLS enrollment | Requires embedded CA |
| Health-gated OTA rollback | Agent verifies artifacts, but automatic health-gate rollback not implemented |

---

## Phase 4: VPN + Monitoring (Weeks 13-16) — NOT STARTED

### 4.1 — WireGuard VPN

Controller:
- VPN group CRUD, IP allocation, peer config distribution
- REST endpoints + DB migration

Agent:
- `agent/internal/vpn/manager.go` (currently empty scaffold):
  - Generate WireGuard key pair via `wgctrl`
  - Create/configure `wg0` interface
  - Handle key rotation

### 4.2 — Enhanced Health Monitoring

Agent:
- Custom health checks from manifest (HTTP, TCP, exec probes)
- Auto-restart services on check failure
- Configurable thresholds and alerting

### 4.3 — Watchdog (full implementation)

- Monitor agent via health endpoint
- SIGTERM then SIGKILL on unresponsive
- Crash logging, <500KB binary
- Resource limit enforcement (CPU, memory caps)

### Phase 4 Deliverable
WireGuard VPN tunnels. Full health monitoring with auto-restart. Watchdog crash recovery.

---

## Phase 5: Dashboard (Weeks 17-20) — COMPLETE (partial)

### What Was Implemented

11-page Next.js 15 SaaS dashboard:
- Fleet overview, device list, device detail, log viewer, manifest editor
- OTA management (artifacts + deployments + progress tracking)
- Organization settings (members, enrollment tokens, API keys)
- Audit log, integrations page
- Landing page with scroll-driven design, animated terminal demo, platform compatibility marquee
- Keycloak OIDC login via NextAuth v5
- Dark/light mode, command palette, responsive design

### What Was Not Completed

| Feature | Status |
|---------|--------|
| Fleet simulation (10-50 agents) | Script exists (`scripts/simulate-fleet.sh`) but not battle-tested |
| End-to-end integration tests | Not started |
| Performance benchmarks | Not started |

### Original Performance Targets

| Metric | Target | Actual |
|--------|--------|--------|
| Agent binary (linux/arm64, UPX) | <5 MB | 2.6 MB ✓ |
| Agent RSS memory | <10 MB | Not measured |
| Reconcile cycle | <1s | Not measured |
| Fleet registration (50 devices) | <30s | Not measured |

---

## Phase 6: Buffer / Polish (Weeks 21-24) — NOT STARTED

### Planned

- Bug fixes and stability hardening
- GPIO plugin (`agent/plugins/gpio/` — empty scaffold)
- Android agent support (see below)
- CI/CD pipeline
- Thesis writing and demo preparation

### Android Agent Support

Android runs the Linux kernel, so the Go agent already cross-compiles to `linux/arm64` and runs on Android (confirmed via Termux). Specific work needed:

| Task | Effort | Description |
|------|--------|-------------|
| `_android.go` build tags | Medium | Service management via `am start/stop`, init.d for Termux |
| `logcat` log source | Low | Replace `journalctl` reader with `logcat -v threadtime` |
| Android default paths | Low | `/data/local/tmp/edgeguardian` (rooted), `$HOME/.edgeguardian` (Termux) |
| Build script update | Low | Add `GOOS=linux GOARCH=arm64` Android target |
| Dashboard OS recognition | Low | Display "Android" with logo in device list and detail pages |
| OTA for Android | Medium | APK sideload (rooted) or binary swap (Termux) |

**Zero-change features** (already work on Android):
- Health metrics (`/proc/stat`, `/proc/meminfo`, `/sys/class/thermal`)
- HTTP + MQTT communication
- BoltDB persistence
- File manager plugin
- OTA download + signature verification
- Reconciliation loop

---

## Quick Reference: Running the Project

```bash
# 1. Start dev infrastructure
docker compose -f deployments/docker-compose.yml up -d

# 2. Build and run controller (Java 21)
cd controller && ./gradlew bootRun

# 3. Start dashboard
cd ui && pnpm install && pnpm dev

# 4. Build agent (current platform)
cd agent && go build -o edgeguardian-agent ./cmd/agent/

# 5. Run agent (with sample config)
./edgeguardian-agent --config ../configs/sample-agent-config.yaml

# 6. Cross-compile for Raspberry Pi (with UPX)
./scripts/build-agent.sh linux arm64

# 7. Verify via REST API
curl http://localhost:8443/api/v1/devices
```
