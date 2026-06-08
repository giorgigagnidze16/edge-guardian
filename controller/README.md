# EdgeGuardian Controller

Spring Boot backend for EdgeGuardian - fleet management, certificate authority, agent binary distribution, and telemetry ingestion for edge devices. Communicates with agents exclusively via MQTT 5.0 (the agent binary download/self-update path is the one exception, over HTTPS).

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 (virtual threads enabled) |
| Spring Boot | 4.0.5 |
| PostgreSQL | 16 (TimescaleDB for telemetry) |
| MQTT | Eclipse Paho v5 (1.2.5) |
| Auth | Keycloak OAuth2 / JWT + API keys |
| PKI | BouncyCastle 1.80 (ECDSA P-256) |
| Object Storage | MinIO 9.0.0 (agent binaries) |
| Migrations | Flyway |
| Observability | OpenTelemetry + Loki |

## Quick Start

The full stack (controller + UI + PostgreSQL + Keycloak + EMQX + MinIO + Loki + Grafana) is deployed
to a local minikube cluster via Helm. From the repo root:

```bash
./scripts/install.sh          # builds controller image, UI image, helm install
```

The controller listens on **port 8443** inside the pod and is exposed at
`http://<minikube-ip>:30443` for edge devices. Dashboard traffic goes through the UI at
`http://<minikube-ip>:30080`. Health check: `GET /actuator/health`.

For tight inner-loop iteration, rebuild the image and roll the deployment:

```bash
eval $(minikube docker-env)
./gradlew bootBuildImage
kubectl -n edgeguardian rollout restart deploy/controller
```

See `../deployments/helm/edgeguardian/README.md` for prod install and secrets management.

## Build & Test

```bash
./gradlew build          # Compile + test
./gradlew test           # Tests only (requires Docker for Testcontainers)
./gradlew compileJava    # Compile only
./gradlew bootRun        # Run with local profile
```

Tests use **Testcontainers** with a real TimescaleDB/PostgreSQL instance - Docker must be running.

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
| Controller -> Device | `enroll/response` | Enrollment result + device token + CA cert |
| Controller -> Device | `state/desired` | Manifest push (on heartbeat) |
| Controller -> Device | `cert/response` | Signed certificate delivery |
| Controller -> Device | `command` | Command dispatch |

### Certificate Authority

Each organization gets an auto-generated **ECDSA P-256 CA** (10-year validity). CA private keys are AES-256-GCM encrypted at rest.

Certificate request security model:
- **INITIAL** (no existing cert) - requires manual admin approval
- **MANIFEST** (no existing cert) - auto-approved (desired-state driven)
- **RENEWAL** (valid `currentSerial`) - auto-approved, old cert rotated
- **Any type + existing valid cert** - **BLOCKED** (compromise detection: all certs revoked, device suspended)

### Agent Binary Distribution & Self-Update

There is a single global agent binary - the EdgeGuardian product itself, not per-tenant
software. CI builds, Ed25519-signs, and publishes each platform via
`POST /api/v1/agent/binaries` (OPERATOR+); the controller verifies the signature and stores
the binary plus a `release.json` sidecar (version + sha256 + signature) in MinIO under
`public/agent/<os>/<arch>/`. Devices fetch over HTTPS: installers pull `GET /api/v1/agent/binary`,
and a running agent polls `GET /api/v1/agent/latest-version` to self-update when its install-time
`auto_update` flag is on (or on demand via `edge-guardian --update`). The watchdog swaps the
binary on agent exit code 42 and rolls back on repeated crashes.

## REST API

All endpoints under `/api/v1/`. Dashboard endpoints require JWT or API key. The only agent-facing
HTTP endpoints are `/api/v1/agent/enroll` (bootstrap enrollment) and the public PKI endpoints
(`/api/v1/pki/crl/**`, `/api/v1/pki/ca-bundle`, `/api/v1/pki/broker-ca`) - everything else an agent
does flows over MQTT.

| Path | Auth | Description |
|------|------|-------------|
| `GET /me` | JWT | Current user + org memberships |
| `/enrollment-tokens` | JWT (ADMIN) | Create/list/revoke enrollment tokens |
| `/devices` | JWT (OPERATOR+) | List/get/delete devices, logs, commands |
| `/devices/{id}/telemetry` | JWT | Raw + hourly aggregated metrics |
| `/certificates` | JWT (OPERATOR+) | List certs, approve/reject/revoke requests, download CA |
| `POST /agent/binaries` | API key (OPERATOR+) | Publish a signed agent binary as the global latest |
| `GET /agent/binary`, `/agent/latest-version` | public | Agent binary download + self-update manifest |
| `/organization` | JWT (VIEWER+) | Org CRUD, member management, audit log |
| `/api-keys` | JWT (ADMIN) | Create/list/revoke API keys |

## Database Migrations

| Version | Description |
|---------|-------------|
| V1 | Devices, device manifests |
| V2 | Users, organizations, members, API keys, enrollment tokens |
| V3 | OTA artifacts/deployments (removed in V10 - superseded by agent binary distribution) |
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

Sourced from `../deployments/helm/edgeguardian/values-dev.yaml`. Override in prod via
`values-prod-secrets.yaml`.

| Service | Credentials |
|---------|-------------|
| PostgreSQL | `admin` / `admin` |
| Keycloak admin | `admin` / `admin` |
| EMQX dashboard | `admin` / `admin` |
| EMQX MQTT (controller) | `controller` / `admin` |
| EMQX MQTT (bootstrap) | `bootstrap` / `admin` |
| MinIO | `admin` / `adminadmin` |
| Grafana | `admin` / `admin` |
