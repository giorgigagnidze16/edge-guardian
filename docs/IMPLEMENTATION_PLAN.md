# EdgeGuardian Implementation Plan

## Current State (Phase 1: Foundation — ~80% complete)

### Go Agent (`agent/`)

| File | Status | Description |
|------|--------|-------------|
| `cmd/agent/main.go` | Done | Entry point: config load, gRPC connect, register, reconciler + heartbeat loop, graceful shutdown |
| `cmd/watchdog/main.go` | Stub | Minimal supervisor with exponential backoff. Full implementation deferred to Phase 4 |
| `internal/config/config.go` | Done | YAML config parser with defaults (device_id, controller_address, grpc_port, labels, etc.) |
| `internal/comms/grpc_client.go` | Done | gRPC client: Register, Heartbeat, GetDesiredState, ReportState. Phase 1 uses insecure transport |
| `internal/reconciler/reconciler.go` | Partial | 30s reconciliation loop. Currently only logs desired state — actual file/service apply deferred to Phase 2 |
| `internal/comms/proto/*.pb.go` | Generated | Protobuf Go code for all 3 services (device_sync, certificate, ota) |
| `internal/{health,ota,vpn,security,storage,provisioner}/` | Empty | Directories ready for Phases 2-4 |
| `plugins/{filemanager,gpio,service}/` | Empty | Directories ready for Phase 2 |

**Binary sizes** (stripped, CGO_ENABLED=0): agent ~12MB, watchdog ~2MB.

### Spring Boot Controller (`controller/`)

| File | Status | Description |
|------|--------|-------------|
| `EdgeGuardianControllerApplication.java` | Done | Spring Boot 3.4.3 entry point |
| `model/Device.java` | Done | Device POJO: deviceId, hostname, arch, os, labels, state enum, timestamps |
| `model/DeviceStatus.java` | Done | Runtime status: CPU, memory, disk, temp, uptime, reconcile info |
| `dto/DeviceDto.java` | Done | REST response record with `from(Device)` factory |
| `service/DeviceRegistry.java` | Done | In-memory ConcurrentHashMap registry. register, heartbeat, findById, findAll, remove |
| `api/DeviceController.java` | Done | REST `GET/DELETE /api/v1/devices`. WebFlux Flux/Mono |
| `grpc/DeviceSyncGrpcService.java` | Done | gRPC server: RegisterDevice (returns demo manifest), Heartbeat, GetDesiredState, ReportState |
| `config/GrpcServerConfig.java` | Done | gRPC server lifecycle on port 9090 |
| `config/WebConfig.java` | Done | CORS for localhost:3000 |
| `mqtt/`, `repository/`, `security/` | Empty | Ready for Phase 2-3 |

**Build**: Gradle 8.14, Spring Boot 3.4.3, gRPC 1.72.0, protobuf-gradle-plugin generates Java stubs from shared `proto/`.

### Protobuf Contracts (`proto/`)

| File | Status | RPC Methods |
|------|--------|-------------|
| `device_sync.proto` | Done | RegisterDevice, Heartbeat, GetDesiredState, ReportState |
| `certificate.proto` | Done | Enroll, RenewCertificate, GetCACertificate |
| `ota.proto` | Done | CheckUpdate, DownloadArtifact (streaming), ReportUpdateStatus |

### Infrastructure

| File | Status |
|------|--------|
| `deployments/docker-compose.yml` | Done — PostgreSQL 16 + Mosquitto 2 |
| `deployments/mosquitto/mosquitto.conf` | Done — anonymous dev config |
| `deployments/systemd/edgeguardian-agent.service` | Done — hardened systemd unit |
| `scripts/generate-proto.sh` | Done — Go protobuf generation |
| `scripts/build-agent.sh` | Done — cross-compile for linux/arm, linux/arm64 |
| `configs/sample-agent-config.yaml` | Done — reference agent config |
| `configs/sample-device-manifest.yaml` | Done — reference device manifest |
| `.gitignore` | Done — Go, Java, Node, IDE, secrets |

### Not Started

| Component | Phase |
|-----------|-------|
| Next.js Dashboard (`ui/`) | Phase 5 |
| Documentation (`docs/`) | Phase 6 |

---

## Phase 2: Core Agent + Database (Weeks 5-8, ~50h)

### 2.1 — Full Reconciliation Engine (agent)

**Goal**: The reconciler actually applies corrections, not just logs.

Files to create/modify:
- `agent/internal/reconciler/reconciler.go` — Extend `reconcileOnce()` to call plugin executors
- `agent/internal/reconciler/diff.go` — Compare desired vs actual state, produce a diff
- `agent/plugins/filemanager/filemanager.go` — FileManager plugin:
  - Read file from disk, compare content/mode/owner to desired
  - Write file if drifted (atomic write via temp file + rename)
  - Set permissions and ownership
- `agent/plugins/service/service.go` — ServiceManager plugin:
  - Check systemd service state via `systemctl is-active`
  - Start/stop/enable/disable services
- `agent/internal/reconciler/plugin.go` — Plugin interface:
  ```go
  type Plugin interface {
      Name() string
      Reconcile(ctx context.Context, spec *pb.ManifestSpec) ([]Action, error)
  }
  ```

Acceptance criteria:
- [ ] Agent detects file content drift and corrects it
- [ ] Agent detects service state drift (stopped vs running) and corrects it
- [ ] Reconcile cycle logged with actions taken
- [ ] Each reconcile completes in <1s

### 2.2 — BoltDB Local Storage (agent)

**Goal**: Agent persists desired state and queues offline messages.

Files to create:
- `agent/internal/storage/store.go` — BoltDB wrapper:
  - Buckets: `desired_state`, `offline_queue`, `agent_meta`
  - `SaveDesiredState(manifest)` / `LoadDesiredState()`
  - `EnqueueMessage(msg)` / `DequeueMessages(limit)` — for offline MQTT/gRPC
  - `SaveMeta(key, value)` / `GetMeta(key)`
- `agent/internal/storage/store_test.go` — Unit tests

Update `cmd/agent/main.go`:
- Open BoltDB at `{data_dir}/agent.db`
- On startup, load cached desired state (survive restarts)
- On gRPC failure, queue heartbeats/state reports for later delivery

Acceptance criteria:
- [ ] Agent survives restart, resumes with cached desired state
- [ ] Offline messages queued and drained on reconnect
- [ ] BoltDB file <1MB for typical workload

### 2.3 — PostgreSQL + Flyway (controller)

**Goal**: Replace in-memory registry with persistent storage.

Files to create:
- `controller/src/main/resources/db/migration/V1__init.sql`:
  ```sql
  CREATE TABLE devices (
      device_id       VARCHAR(255) PRIMARY KEY,
      hostname        VARCHAR(255),
      architecture    VARCHAR(50),
      os              VARCHAR(50),
      agent_version   VARCHAR(50),
      labels          JSONB DEFAULT '{}',
      state           VARCHAR(20) DEFAULT 'ONLINE',
      registered_at   TIMESTAMPTZ DEFAULT now(),
      last_heartbeat  TIMESTAMPTZ,
      status          JSONB
  );

  CREATE TABLE device_manifests (
      id              BIGSERIAL PRIMARY KEY,
      device_id       VARCHAR(255) REFERENCES devices(device_id),
      version         BIGINT NOT NULL,
      manifest        JSONB NOT NULL,
      created_at      TIMESTAMPTZ DEFAULT now(),
      UNIQUE(device_id, version)
  );
  ```
- `controller/.../repository/DeviceRepository.java` — Spring Data JPA repository
- `controller/.../repository/DeviceManifestRepository.java` — Manifest repository
- `controller/.../model/DeviceEntity.java` — JPA entity (replaces plain POJO)

Update:
- `build.gradle` — Add `spring-boot-starter-data-jpa`, `postgresql`, `flyway-core`
- `application.yaml` — Add datasource config (pointing at docker-compose PostgreSQL)
- `DeviceRegistry.java` — Swap ConcurrentHashMap for JPA calls
- `DeviceSyncGrpcService.java` — Read/write manifests from DB

Acceptance criteria:
- [ ] `docker compose up` creates schema via Flyway
- [ ] Device registration persists across controller restarts
- [ ] Manifests stored as JSONB, versioned
- [ ] REST API returns persisted devices

### 2.4 — MQTT Integration (both sides)

**Goal**: Bidirectional MQTT for telemetry and commands.

Topic structure:
```
edgeguardian/devices/{device_id}/telemetry    # agent -> controller (QoS 1)
edgeguardian/devices/{device_id}/commands      # controller -> agent (QoS 1)
edgeguardian/devices/{device_id}/state         # agent -> controller (QoS 1, retained)
edgeguardian/broadcast/commands                # controller -> all agents (QoS 1)
```

Agent files to create:
- `agent/internal/comms/mqtt_client.go`:
  - Connect to Mosquitto (Phase 1: plain TCP, Phase 3: mTLS)
  - Publish telemetry (CPU, mem, disk, temp) every 30s
  - Subscribe to `commands` topic, dispatch to reconciler/OTA
  - Offline buffering via BoltDB queue
- `agent/internal/health/collector.go`:
  - Collect system metrics: CPU (via /proc/stat), RAM (/proc/meminfo), disk (statfs), temperature (/sys/class/thermal)
  - Return as `DeviceStatus` proto message

Controller files to create:
- `controller/.../mqtt/MqttConfig.java` — Eclipse Paho MQTT client bean
- `controller/.../mqtt/TelemetryListener.java` — Subscribe to `+/telemetry`, update device status
- `controller/.../mqtt/CommandPublisher.java` — Publish commands to specific device or broadcast

Update `application.yaml` with MQTT broker URL.

Acceptance criteria:
- [ ] Agent publishes telemetry every 30s
- [ ] Controller receives and stores telemetry
- [ ] Controller can send command, agent receives and logs it
- [ ] Agent queues messages when MQTT is down, drains on reconnect

### Phase 2 Deliverable
Agent reconciles file/service state, persists data in BoltDB, communicates over MQTT. Controller uses PostgreSQL. Full offline resilience.

---

## Phase 3: Security + OTA (Weeks 9-12, ~60h)

### 3.1 — Embedded Certificate Authority (controller)

Files to create:
- `controller/.../security/CertificateAuthority.java`:
  - Generate self-signed root CA on first boot (Bouncy Castle)
  - Store CA key in encrypted file or HSM-backed keystore
  - Issue X.509 client certs from CSRs (valid 90 days)
  - Maintain CRL (Certificate Revocation List)
- `controller/.../security/EnrollmentService.java`:
  - Generate single-use enrollment tokens (UUID, 1h expiry)
  - Validate tokens, trigger cert issuance
- `controller/.../grpc/CertificateGrpcService.java`:
  - Implement `Enroll`, `RenewCertificate`, `GetCACertificate` RPCs
- `controller/.../api/CertificateController.java`:
  - REST endpoints: create enrollment token, list certs, revoke cert

### 3.2 — Agent mTLS + Enrollment

Files to create:
- `agent/internal/security/enrollment.go`:
  - Discover controller via mDNS (zeroconf)
  - Exchange enrollment token over server-only TLS
  - Generate key pair, CSR, send via gRPC
  - Store issued cert + CA cert on disk (encrypted)
- `agent/internal/security/tls.go`:
  - Build `tls.Config` with client cert
  - `GetCertificate` callback for hot-swap rotation
  - Background goroutine: monitor cert expiry, renew at <20% lifetime remaining
- `agent/internal/security/secrets.go`:
  - AES-256-GCM encryption at rest (Argon2id key derivation from device-specific seed)
  - Encrypt private keys and sensitive config on disk

Update:
- gRPC client: switch from `insecure.NewCredentials()` to mTLS `credentials.NewTLS()`
- MQTT client: add TLS config with client cert
- Mosquitto config: require client certs on port 8883

### 3.3 — OTA Pipeline

Controller files:
- `controller/.../service/OtaService.java`:
  - Artifact upload (binary + Ed25519 public key signature)
  - Target devices by label selector
  - Rolling deployment (configurable batch size, pause between batches)
  - Track deployment status per device
- `controller/.../grpc/OtaGrpcService.java`:
  - Implement `CheckUpdate`, `DownloadArtifact` (streaming), `ReportUpdateStatus`
- `controller/.../api/DeploymentController.java`:
  - REST: upload artifact, create deployment, list deployments, get status
- `controller/src/main/resources/db/migration/V2__ota.sql`:
  - `artifacts` table (id, version, sha256, ed25519_sig, size, upload_time)
  - `deployments` table (id, artifact_id, target_labels, status, created_at)
  - `deployment_devices` table (deployment_id, device_id, status, message, updated_at)

Agent files:
- `agent/internal/ota/manager.go`:
  - Check for updates (periodic or on command)
  - Download artifact via gRPC streaming (with resume)
  - Verify SHA-256 hash + Ed25519 signature
  - Staged apply: backup current -> write new -> health gate -> commit or rollback
- `agent/internal/ota/health_gate.go`:
  - After applying update, run health checks every 2s for 30s
  - If any check fails, trigger rollback
  - Report result to controller

### Phase 3 Deliverable
All connections secured with mTLS. Zero-touch enrollment. OTA with cryptographic verification, health-gated rollback. **Key thesis demo point.**

---

## Phase 4: VPN + Monitoring (Weeks 13-16, ~55h)

### 4.1 — WireGuard VPN

Controller:
- `controller/.../service/VpnService.java`:
  - VPN group CRUD (name, subnet, devices)
  - IP allocation from subnet (10.100.x.x/24 per group)
  - Peer config generation and distribution
  - Key rotation trigger (every 24h)
- `controller/.../api/VpnController.java`:
  - REST: create/delete VPN groups, add/remove devices, get peer list
- `controller/src/main/resources/db/migration/V3__vpn.sql`:
  - `vpn_groups` (id, name, subnet, created_at)
  - `vpn_peers` (id, group_id, device_id, private_ip, public_key, endpoint)

Agent:
- `agent/internal/vpn/manager.go`:
  - Generate WireGuard key pair via `wgctrl`
  - Create/configure `wg0` interface
  - Add/remove peers as directed by controller
  - Handle key rotation: generate new keypair, report public key, swap atomically
  - Persistent keepalive (25s) for NAT traversal
- `agent/internal/vpn/nat.go`:
  - STUN-based external IP detection
  - Fallback: controller as hub relay for double-NAT

### 4.2 — Enhanced Health Monitoring

Agent:
- `agent/internal/health/monitor.go`:
  - System metrics: CPU, RAM, disk, temperature, network I/O
  - Custom health checks from manifest (HTTP, TCP, exec)
  - Auto-restart services on check failure (configurable threshold)
  - Expose health summary for watchdog
- `agent/internal/health/collector.go` — Extend from Phase 2 with custom checks

### 4.3 — Watchdog (full implementation)

- `agent/cmd/watchdog/main.go`:
  - Monitor agent via Unix socket or HTTP health endpoint
  - If agent unresponsive for 3 consecutive checks (10s each), SIGTERM then SIGKILL
  - Log crash events to dedicated file
  - Report crash count via MQTT (best effort)
  - <500KB binary target

### 4.4 — Structured Logging + Log Streaming

- `agent/internal/comms/log_stream.go`:
  - Stream structured logs (JSON) via MQTT topic `edgeguardian/devices/{id}/logs`
  - Configurable level filter (only stream WARN+ to controller)
  - Ring buffer for recent logs (accessible locally)

### Phase 4 Deliverable
Declarative WireGuard VPN tunnels between devices. Full health monitoring with auto-restart. Watchdog crash recovery. Structured log streaming.

---

## Phase 5: UI + Testing (Weeks 17-20, ~50h)

### 5.1 — Next.js Dashboard

Initialize project:
```bash
cd ui && npx create-next-app@latest . --typescript --tailwind --eslint --app --src-dir
npx shadcn-ui@latest init
```

Pages and components:
- **Dashboard** (`app/page.tsx`): fleet summary cards (online/degraded/offline counts), recent events, system health
- **Device List** (`app/devices/page.tsx`): sortable/filterable table, status badges, bulk actions
- **Device Detail** (`app/devices/[id]/page.tsx`): tabs (overview, config, logs, VPN, deployments)
- **Deployments** (`app/deployments/page.tsx`): deployment list, create new, rollback, progress tracking
- **VPN Groups** (`app/vpn/page.tsx`): group list, peer topology diagram, add/remove devices
- **YAML Editor** (`app/editor/page.tsx`): Monaco Editor for device manifests, syntax validation, deploy
- **Certificates** (`app/certificates/page.tsx`): cert list, enrollment tokens, revocation
- **Settings** (`app/settings/page.tsx`): controller config, user management (Phase 6)

Shared infrastructure:
- `lib/api.ts` — API client (fetch wrapper, typed responses)
- `lib/hooks/useDevices.ts` — TanStack Query hooks for data fetching
- `components/layout/` — Sidebar navigation, header, breadcrumbs
- WebSocket connection for real-time updates (device state changes, deployment progress)
- Recharts for metric visualization (CPU, memory, disk over time)

### 5.2 — Fleet Simulation

- `scripts/simulate-fleet.sh`:
  - Spawn 10-50 agent processes with unique configs
  - Each agent registers, sends telemetry, responds to commands
  - Simulate network partitions (kill/restart random agents)
  - Verify all agents re-register and reconcile after recovery

### 5.3 — Integration Tests

- End-to-end test: provision device -> deploy manifest -> verify file written -> update manifest -> verify reconciliation
- OTA test: upload artifact -> deploy -> verify apply -> inject health failure -> verify rollback
- VPN test: create group with 2 devices -> verify WireGuard tunnel -> ping between devices
- Cert rotation test: fast-forward cert expiry -> verify hot swap -> no connection drop
- Offline resilience: disconnect agent -> queue messages -> reconnect -> verify drain

### 5.4 — Performance Benchmarks

| Metric | Target |
|--------|--------|
| Agent binary size (linux/arm64, stripped) | <5MB |
| Agent RSS memory | <10MB |
| OTA download + apply (LAN, 10MB artifact) | <30s |
| Reconcile cycle duration | <1s |
| WireGuard tunnel establishment | <5s |
| Cert rotation (zero-downtime) | <2s |
| Fleet registration (50 devices concurrent) | <30s total |

### Phase 5 Deliverable
Complete web dashboard, fleet simulation, benchmarked performance, integration test suite.

---

## Phase 6: Buffer / Polish (Weeks 21-24, ~40h)

- Bug fixes and stability improvements
- Optional extensions:
  - GPIO plugin (`agent/plugins/gpio/`) for Raspberry Pi GPIO control
  - RBAC enforcement in controller (Spring Security roles, JWT tokens)
  - Anomaly detection (simple threshold-based alerting on metrics)
  - ESP32 MQTT shim (lightweight C client for constrained devices)
- Complete thesis writing
- Prepare demo (video recording, live presentation)
- Final documentation

---

## Architecture Diagram (target state)

```
                    +-------------------+
                    |   Next.js UI      |  (Port 3000)
                    |   shadcn/ui       |
                    |   TanStack Query  |
                    +--------+----------+
                             | REST / WebSocket
                    +--------v----------+
                    | Spring Boot       |  (8443 REST, 9090 gRPC)
                    | Controller        |
                    |  - DeviceRegistry |  (JPA + PostgreSQL)
                    |  - gRPC Server    |  (DeviceSync, Cert, OTA)
                    |  - MQTT Bridge    |  (Paho client)
                    |  - Embedded CA    |  (Bouncy Castle)
                    |  - VPN Orchest.   |  (WireGuard groups)
                    |  - REST API       |  (WebFlux)
                    +---+------+--------+
                        |      |
              +---------+      +----------+
              | PostgreSQL 16  | Mosquitto |  (1883 / 8883 mTLS)
              | (Flyway)       | MQTT 5.0  |
              +----------------+-----------+
                        |
          mTLS gRPC + MQTT + WireGuard VPN
                        |
         +--------------+--------------+
         |              |              |
    +----v----+   +-----v----+   +-----v----+
    | Go Agent|   | Go Agent |   | Go Agent |
    | <5MB    |   | <5MB     |   | <5MB     |
    |---------|   |----------|   |----------|
    | Recon.  |   | Recon.   |   | Recon.   |
    | OTA     |   | OTA      |   | OTA      |
    | VPN     |   | VPN      |   | VPN      |
    | Health  |   | Health   |   | Health   |
    | BoltDB  |   | BoltDB   |   | BoltDB   |
    +---------+   +----------+   +----------+
         |
    +----v----+
    | Watchdog|  (<500KB, per device)
    +---------+
```

---

## Quick Reference: Running the Project

```bash
# 1. Start dev infrastructure
docker compose -f deployments/docker-compose.yml up -d

# 2. Generate protobuf code (if proto files changed)
./scripts/generate-proto.sh

# 3. Build and run controller
cd controller && gradle bootRun

# 4. Build agent (current platform)
cd agent && go build -o edgeguardian-agent ./cmd/agent/

# 5. Run agent (with sample config)
./edgeguardian-agent --config ../configs/sample-agent-config.yaml

# 6. Cross-compile for Raspberry Pi
./scripts/build-agent.sh linux arm64

# 7. Verify via REST API
curl http://localhost:8443/api/v1/devices
```
