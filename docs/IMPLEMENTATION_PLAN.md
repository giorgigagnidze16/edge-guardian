# EdgeGuardian Implementation Plan

## Current State (Phase 2: Core — Complete)

### Go Agent (`agent/`)

| File | Status | Description |
|------|--------|-------------|
| `cmd/agent/main.go` | Done | Entry point: BoltDB, HTTP client, MQTT, plugins, health collector, graceful shutdown |
| `cmd/watchdog/main.go` | Stub | Minimal supervisor with exponential backoff. Full implementation Phase 4 |
| `internal/config/config.go` | Done | YAML config with MQTT section, controller_port, data_dir |
| `internal/model/manifest.go` | Done | All data types: manifests, status, DTOs (plain Go structs, JSON tags) |
| `internal/comms/http_client.go` | Done | HTTP/JSON controller client with exponential backoff retry (3 attempts) |
| `internal/comms/mqtt_client.go` | Done | MQTT client: telemetry publish, command subscribe, offline BoltDB queue |
| `internal/storage/store.go` | Done | BoltDB: desired state cache, offline message queue, metadata |
| `internal/reconciler/reconciler.go` | Done | Periodic reconciliation loop with plugin dispatch |
| `internal/reconciler/plugin.go` | Done | Plugin interface: Name(), CanHandle(), Reconcile() |
| `internal/reconciler/diff.go` | Done | Manifest → ResourceSpec conversion for plugin dispatch |
| `internal/health/collector.go` | Done | System metrics: CPU, RAM, disk, temperature, uptime (Linux /proc) |
| `plugins/filemanager/filemanager.go` | Done | Atomic file writes, mode/owner management, parent dir creation |
| `plugins/service/service.go` | Done | Systemd service management via systemctl |
| `internal/{ota,vpn,security,provisioner}/` | Empty | Scaffolding for Phases 3-4 |
| `plugins/gpio/` | Empty | Optional Phase 6 |

**Binary size** (linux/arm64, stripped + UPX): **2.6 MB** (target: <5 MB)

**Tests**: storage, reconciler/diff, filemanager, service — all pass.

### Spring Boot Controller (`controller/`)

| File | Status | Description |
|------|--------|-------------|
| `EdgeGuardianControllerApplication.java` | Done | Spring Boot 3.4.3 entry point |
| `model/Device.java` | Done | JPA @Entity: deviceId, hostname, arch, os, labels (JSONB), state, timestamps |
| `model/DeviceStatus.java` | Done | Runtime metrics POJO |
| `model/DeviceManifestEntity.java` | Done | JPA @Entity: device manifest with versioning, JSONB spec |
| `dto/DeviceDto.java` | Done | Dashboard REST response record |
| `dto/Agent*.java` (7 files) | Done | Agent REST request/response records |
| `service/DeviceRegistry.java` | Done | JPA-backed: register, heartbeat, manifest CRUD |
| `repository/DeviceRepository.java` | Done | Spring Data JPA: findByDeviceId, findByState |
| `repository/DeviceManifestRepository.java` | Done | Spring Data JPA: manifest CRUD |
| `api/DeviceController.java` | Done | Dashboard REST: GET/DELETE /api/v1/devices |
| `api/AgentApiController.java` | Done | Agent REST: register, heartbeat, desired-state, report-state |
| `config/MqttConfig.java` | Done | Eclipse Paho MQTT v5 client bean |
| `config/WebConfig.java` | Done | CORS for localhost:3000 |
| `mqtt/TelemetryListener.java` | Done | Subscribes to device telemetry, updates DB |
| `mqtt/CommandPublisher.java` | Done | Publishes commands to devices |
| `security/` | Empty | Phase 3 |

**Build**: Gradle 8.14, Spring Boot 3.4.3, Java 21. No gRPC (see ADR-001).

**Database**: PostgreSQL 16 with Flyway migration (`V1__init.sql`).

### Infrastructure

| File | Status |
|------|--------|
| `deployments/docker-compose.yml` | Done — PostgreSQL 16 + Mosquitto 2 |
| `deployments/mosquitto/mosquitto.conf` | Done — anonymous dev config |
| `deployments/systemd/edgeguardian-agent.service` | Done — hardened systemd unit |
| `scripts/build-agent.sh` | Done — cross-compile, UPX, 5MB size gate |
| `configs/sample-agent-config.yaml` | Done — reference agent config |
| `configs/sample-device-manifest.yaml` | Done — reference device manifest |
| `docs/ADR-001-agent-http-over-grpc.md` | Done — HTTP/JSON decision rationale |
| `docs/ADR-002-upx-binary-compression.md` | Done — UPX decision rationale |

### Not Started

| Component | Phase |
|-----------|-------|
| Next.js Dashboard (`ui/`) | Phase 5 |

---

## Phase 3: Security + OTA (Weeks 9-12)

### 3.1 — Embedded Certificate Authority (controller)

Files to create:
- `controller/.../security/CertificateAuthority.java`:
  - Generate self-signed root CA on first boot (Bouncy Castle)
  - Issue X.509 client certs from CSRs (valid 90 days)
  - Maintain CRL (Certificate Revocation List)
- `controller/.../security/EnrollmentService.java`:
  - Generate single-use enrollment tokens (UUID, 1h expiry)
  - Validate tokens, trigger cert issuance
- `controller/.../api/CertificateController.java`:
  - REST: create enrollment token, list certs, revoke cert

### 3.2 — Agent mTLS + Enrollment

Files to create:
- `agent/internal/security/enrollment.go`:
  - Exchange enrollment token over server-only TLS
  - Generate key pair, CSR, send via HTTP
  - Store issued cert + CA cert on disk
- `agent/internal/security/tls.go`:
  - Build `tls.Config` with client cert
  - Background goroutine: renew cert at <20% lifetime remaining

Update:
- HTTP client: switch to mTLS `tls.Config`
- MQTT client: add TLS config with client cert

### 3.3 — OTA Pipeline

Controller files:
- `controller/.../service/OtaService.java`:
  - Artifact upload (binary + Ed25519 signature)
  - Rolling deployment (configurable batch size)
  - Track status per device
- `controller/.../api/DeploymentController.java`:
  - REST: upload artifact, create deployment, list, status
- `controller/src/main/resources/db/migration/V2__ota.sql`

Agent files:
- `agent/internal/ota/manager.go`:
  - Check for updates, download via HTTP streaming
  - Verify SHA-256 hash + Ed25519 signature
  - Staged apply: backup → write → health gate → commit or rollback

### Phase 3 Deliverable
All connections secured with mTLS. Zero-touch enrollment. OTA with cryptographic verification and health-gated rollback.

---

## Phase 4: VPN + Monitoring (Weeks 13-16)

### 4.1 — WireGuard VPN

Controller:
- VPN group CRUD, IP allocation, peer config distribution
- REST endpoints + DB migration

Agent:
- `agent/internal/vpn/manager.go`:
  - Generate WireGuard key pair via `wgctrl`
  - Create/configure `wg0` interface
  - Handle key rotation

### 4.2 — Enhanced Health Monitoring

Agent:
- Custom health checks from manifest (HTTP, TCP, exec)
- Auto-restart services on check failure

### 4.3 — Watchdog (full implementation)

- Monitor agent via health endpoint
- SIGTERM then SIGKILL on unresponsive
- Crash logging, <500KB binary

### Phase 4 Deliverable
WireGuard VPN tunnels. Full health monitoring with auto-restart. Watchdog crash recovery.

---

## Phase 5: UI + Testing (Weeks 17-20)

### 5.1 — Next.js Dashboard

Pages: Dashboard, Device List, Device Detail, Deployments, VPN Groups, YAML Editor, Certificates.

Stack: Next.js 14+, TypeScript, shadcn/ui, TanStack Query, Recharts, Monaco Editor.

### 5.2 — Fleet Simulation

- Spawn 10-50 agent processes with unique configs
- Simulate network partitions

### 5.3 — Integration Tests

- End-to-end: provision → deploy manifest → verify reconciliation
- OTA: upload → deploy → verify → inject failure → verify rollback
- Offline resilience: disconnect → queue → reconnect → drain

### 5.4 — Performance Benchmarks

| Metric | Target |
|--------|--------|
| Agent binary (linux/arm64, UPX) | <5 MB |
| Agent RSS memory | <10 MB |
| Reconcile cycle | <1s |
| Fleet registration (50 devices) | <30s |

---

## Phase 6: Buffer / Polish (Weeks 21-24)

- Bug fixes and stability
- Optional: GPIO plugin, RBAC, anomaly detection
- Thesis writing, demo preparation

---

## Quick Reference: Running the Project

```bash
# 1. Start dev infrastructure
docker compose -f deployments/docker-compose.yml up -d

# 2. Build and run controller
cd controller && ./gradlew bootRun

# 3. Build agent (current platform)
cd agent && go build -o edgeguardian-agent ./cmd/agent/

# 4. Run agent (with sample config)
./edgeguardian-agent --config ../configs/sample-agent-config.yaml

# 5. Cross-compile for Raspberry Pi (with UPX)
./scripts/build-agent.sh linux arm64

# 6. Verify via REST API
curl http://localhost:8443/api/v1/devices
```