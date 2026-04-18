# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

EdgeGuardian — Kubernetes-style IoT device management without containers. Three components in a monorepo: Go agent, Spring Boot controller, Next.js dashboard.

## Build & Test Commands

### Go Agent (`agent/`)
```bash
# Build (from repo root)
./scripts/build-agent.sh                    # native
./scripts/build-agent.sh linux arm64        # RPi 64-bit
./scripts/build-agent.sh linux arm          # RPi 32-bit
./scripts/build-agent.sh android arm64      # Android

# Unit tests (run from agent/)
go test ./...

# Single package test
go test ./internal/config
go test ./internal/reconciler -run TestReconcilerDiff

# Integration tests (require Docker — Linux, systemd + MQTT broker inside container)
docker build -f Dockerfile.test -t eg-agent-test .
docker run --privileged eg-agent-test
```

### Spring Boot Controller (`controller/`)
```bash
# Build
./gradlew build

# All tests (unit + integration via Testcontainers — requires Docker running)
./gradlew test

# Run the application
./gradlew bootRun
```

### Next.js Dashboard (`ui/`)
```bash
npm run dev      # Dev server with Turbopack
npm run build    # Production build
npm run lint     # ESLint
```

### Infrastructure (Kubernetes via Helm)

Canonical dev bring-up (minikube) — one command:

```bash
./scripts/install.sh                   # builds controller + UI images into minikube's docker, helm upgrade --install with values-dev.yaml
```

Manual equivalent:

```bash
eval $(minikube docker-env)
cd controller && ./gradlew bootBuildImage
cd ../ui && docker build -t edgeguardian/ui:latest .
helm upgrade --install edgeguardian deployments/helm/edgeguardian \
  -n edgeguardian --create-namespace \
  -f deployments/helm/edgeguardian/values-dev.yaml
```

See `deployments/helm/edgeguardian/README.md` for prod setup (cert-manager + Let's Encrypt
ClusterIssuer, `values-prod-secrets.yaml`), first-org bootstrap, and NodePort mapping for
edge devices. `helm install` is the only supported deployment path — docker-compose and
Kustomize were removed.

## Architecture

**Go Agent** (<5MB binary, CGO_ENABLED=0): Runs on edge devices (RPi, Android, Windows, macOS). Reconciliation loop polls controller for desired state, diffs against actual, applies changes via plugins. Offline-first with BoltDB persistence and MQTT command channel. Watchdog binary supervises agent with crash recovery and OTA binary swap (exit code 42).

**Spring Boot Controller** (Java 21, virtual threads): REST API for dashboard + agent enrollment. PostgreSQL with Flyway migrations. Keycloak OAuth2 for dashboard auth. Only `/api/v1/agent/enroll` and the public PKI endpoints are unauthenticated — all other `/api/v1/**` paths require JWT or API key. Agent data-plane comms (heartbeat, telemetry, commands, logs, cert signing, OTA status) are MQTT 5 via Eclipse Paho, not HTTP. Multi-tenant with organization-scoped resources.

**Next.js Dashboard**: 11-page app with Keycloak SSO via NextAuth. TanStack Query for server state. shadcn/ui components. Monaco editor for device manifests. API calls go to controller at `NEXT_PUBLIC_API_URL` (default `http://localhost:8443`).

## Key Patterns

- **Agent platform code**: Build-tag-separated files (`_linux.go`, `_android.go`, `_windows.go`, `_darwin.go`, `_fallback.go`). Android implies Linux in Go, so Linux files need `//go:build linux && !android`.
- **Agent binary size**: Must stay under 5MB. gRPC was removed for this reason (ADR-001). UPX compression used (ADR-002).
- **Agent OTA flow**: Controller stores artifacts → agent polls via heartbeat → downloads binary → watchdog swaps on exit code 42 → rollback on repeated crashes.
- **Controller DTOs**: Separate DTO classes in `dto/` package for all API request/response types. Entities in `model/` are JPA-annotated.
- **Controller security**: `SecurityConfig.java` — PKI endpoints (`/api/v1/pki/crl/**`, `/api/v1/pki/ca-bundle`, `/api/v1/pki/broker-ca`) and agent enroll are `permitAll`; everything under `/api/v1/**` requires JWT or API key; anyRequest is `denyAll`. Device/telemetry endpoints are org-scoped via `@PreAuthorize("@orgSecurity.hasMinRole(...)")` + `findByIdForOrganization` — cross-tenant access returns 404.
- **Database migrations**: Flyway in `controller/src/main/resources/db/migration/` — `V1__core` (devices, users, orgs, API keys, enrollment tokens), `V2__ota`, `V3__commands`, `V4__pki` (cert requests, issued certs, CRL, org CAs), `V5__telemetry` (TimescaleDB hypertable + compression + retention), `V6__seed_default_org`.
- **PKI / mTLS**: agents bootstrap on tcp/1883 with shared `bootstrap` credentials + CSR in enroll request; receive signed identity cert; reconnect on ssl/8883 with mTLS. cert-manager issues the broker server cert; trust-manager distributes its CA. Revocation: `CrlService.rebuild` + `EmqxAdminClient.kickout` fire on every revoke. Leaf certs carry a CDP extension pointing to `/api/v1/pki/crl/{orgId}.crl`.
- **Integration tests**: Agent uses Docker container with systemd + Mosquitto (`//go:build integration`). Controller uses Testcontainers with PostgreSQL.
- **UI API layer**: `lib/api-client.ts` wraps fetch with auth token injection. Domain-specific functions in `lib/api/`.

## Dev Credentials

Defined in `deployments/helm/edgeguardian/values-dev.yaml`. Postgres `admin:admin`,
Keycloak admin `admin:admin`, EMQX dashboard `admin:admin`, MQTT users
`controller:admin` + `bootstrap:admin`, MinIO `admin:adminadmin`, Grafana `admin:admin`.
For prod, copy `values-prod-secrets.yaml.example` to `values-prod-secrets.yaml` (gitignored)
and pass it as a second `-f` to `helm upgrade --install`.