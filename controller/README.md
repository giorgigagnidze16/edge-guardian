# EdgeGuardian Controller

Spring Boot backend for EdgeGuardian — fleet management, certificate authority, OTA orchestration, and telemetry ingestion for edge devices. Communicates with agents exclusively via MQTT 5.0.

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 (virtual threads enabled) |
| Spring Boot | 4.0.5 |
| PostgreSQL | 16 (TimescaleDB for telemetry) |
| MQTT | Eclipse Paho v5 (1.2.5) |
| Auth | Keycloak OAuth2 / JWT + API keys |
| PKI | BouncyCastle 1.80 (ECDSA P-256) |
| Object Storage | MinIO 9.0.0 (OTA artifacts) |
| Migrations | Flyway |
| Observability | OpenTelemetry + Loki |

## Quick Start

```bash
# 1. Start infrastructure (PostgreSQL, Keycloak, EMQX, MinIO, Loki, Grafana)
docker compose -f ../deployments/docker-compose.yml up -d

# 2. Provision EMQX MQTT users (run once after fresh start)
docker compose -f ../deployments/docker-compose.yml run --rm emqx-init

# 3. Run the controller
./gradlew bootRun
```

The controller starts on **port 8443**. Health check: `http://localhost:8443/actuator/health`

## Build & Test

```bash
./gradlew build          # Compile + test
./gradlew test           # Tests only (requires Docker for Testcontainers)
./gradlew compileJava    # Compile only
./gradlew bootRun        # Run with local profile
```

Tests use **Testcontainers** with a real TimescaleDB/PostgreSQL instance — Docker must be running.

## Architecture

### Multi-Tenancy

All resources are scoped to an **Organization**. Users are synced from Keycloak on first JWT login and auto-assigned a personal org. Role hierarchy: `VIEWER < OPERATOR < ADMIN < OWNER`.

### MQTT Communication

The controller is the sole bridge between the dashboard and edge devices. All device communication flows through MQTT topics under `edgeguardian/device/{deviceId}/...`:

| Direction | Topic Suffix | Purpose |
|-----------|-------------|---------|
| Device -> Controller | `enroll/request` | Device enrollment |
| Device -> Controller | `heartbeat` | Liveness + manifest version check |
| Device -> Controller | `telemetry` | CPU/memory/disk/temp metrics |
| Device -> Controller | `logs` | Log forwarding to Loki |
| Device -> Controller | `cert/request` | CSR submission |
| Device -> Controller | `command/result` | Command execution results |
| Device -> Controller | `ota/status` | OTA deployment progress |
| Controller -> Device | `enroll/response` | Enrollment result + device token + CA cert |
| Controller -> Device | `state/desired` | Manifest push (on heartbeat) |
| Controller -> Device | `cert/response` | Signed certificate delivery |
| Controller -> Device | `command` | Command dispatch |

### Certificate Authority

Each organization gets an auto-generated **ECDSA P-256 CA** (10-year validity). CA private keys are AES-256-GCM encrypted at rest.

Certificate request security model:
- **INITIAL** (no existing cert) — requires manual admin approval
- **MANIFEST** (no existing cert) — auto-approved (desired-state driven)
- **RENEWAL** (valid `currentSerial`) — auto-approved, old cert rotated
- **Any type + existing valid cert** — **BLOCKED** (compromise detection: all certs revoked, device suspended)

### OTA Updates

Artifacts are uploaded to MinIO. Deployments target devices via label selectors. Agents download artifacts via presigned S3 URLs. Per-device progress tracking through the full lifecycle: `PENDING -> DOWNLOADING -> VERIFYING -> INSTALLING -> SUCCESS/FAILED/ROLLED_BACK`.

## REST API

All endpoints under `/api/v1/`. Dashboard endpoints require JWT or API key. Agent endpoints use device tokens.

| Path | Auth | Description |
|------|------|-------------|
| `GET /me` | JWT | Current user + org memberships |
| `/enrollment-tokens` | JWT (ADMIN) | Create/list/revoke enrollment tokens |
| `/devices` | JWT (OPERATOR+) | List/get/delete devices, logs, commands |
| `/devices/{id}/telemetry` | JWT | Raw + hourly aggregated metrics |
| `/certificates` | JWT (OPERATOR+) | List certs, approve/reject/revoke requests, download CA |
| `/ota/artifacts` | JWT (OPERATOR+) | Upload/list/delete OTA artifacts |
| `/ota/deployments` | JWT (OPERATOR+) | Create/list/track OTA deployments |
| `/organization` | JWT (VIEWER+) | Org CRUD, member management, audit log |
| `/api-keys` | JWT (ADMIN) | Create/list/revoke API keys |

## Database Migrations

| Version | Description |
|---------|-------------|
| V1 | Devices, device manifests |
| V2 | Users, organizations, members, API keys, enrollment tokens |
| V3 | OTA artifacts, deployments, device statuses |
| V4 | TimescaleDB hypertable for telemetry (compression + retention policies) |
| V5 | Device commands and execution results |
| V6 | Certificate requests, issued certificates, organization CAs |

## Configuration

Managed via Spring profiles (`application-{profile}.yaml`):

| Profile | Purpose | Key Properties |
|---------|---------|----------------|
| `db` | PostgreSQL connection | `spring.datasource.*` |
| `mqtt` | MQTT broker | `edgeguardian.controller.mqtt.broker-url`, `username`, `password` |
| `security` | OAuth2/Keycloak | `spring.security.oauth2.resourceserver.jwt.issuer-uri` |
| `ca` | Certificate Authority | `edgeguardian.controller.ca.encryption-key` (env: `CA_ENCRYPTION_KEY`) |
| `storage` | MinIO | `edgeguardian.controller.storage.*` |
| `logging` | Loki integration | `edgeguardian.controller.loki.*` |
| `monitoring` | OpenTelemetry | `management.tracing.*` |
| `local` | Dev CORS (allow all) | `edgeguardian.controller.cors.*` |

## Dev Credentials

| Service | Credentials |
|---------|-------------|
| PostgreSQL | `edgeguardian` / `edgeguardian-dev` |
| Keycloak admin | `admin` / `admin` |
| EMQX dashboard | `admin` / `admin-secret` |
| EMQX (controller) | `controller` / `controller-secret` |
| EMQX (test device) | `test-device` / `test-device-secret` |
| MinIO | `edgeguardian` / `edgeguardian-dev` |
| Grafana | `admin` / `admin` |
