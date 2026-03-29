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

# Integration tests (require Docker — Linux, systemd, Mosquitto inside container)
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

### Infrastructure (local dev)
```bash
docker compose -f deployments/docker-compose.yml up -d
# Starts: PostgreSQL (5432), Keycloak (9090), EMQX (1883), Loki (3100), Grafana (3000)
```

## Architecture

**Go Agent** (<5MB binary, CGO_ENABLED=0): Runs on edge devices (RPi, Android, Windows, macOS). Reconciliation loop polls controller for desired state, diffs against actual, applies changes via plugins. Offline-first with BoltDB persistence and MQTT command channel. Watchdog binary supervises agent with crash recovery and OTA binary swap (exit code 42).

**Spring Boot Controller** (Java 21, virtual threads): REST API for agent communication and dashboard. PostgreSQL with Flyway migrations. Keycloak OAuth2 for dashboard auth. Agent endpoints (`/api/v1/agent/*`) are unauthenticated; dashboard endpoints require JWT or API key. MQTT 5 via Eclipse Paho for telemetry ingestion and command publishing. Multi-tenant with organization-scoped resources.

**Next.js Dashboard**: 11-page app with Keycloak SSO via NextAuth. TanStack Query for server state. shadcn/ui components. Monaco editor for device manifests. API calls go to controller at `NEXT_PUBLIC_API_URL` (default `http://localhost:8443`).

## Key Patterns

- **Agent platform code**: Build-tag-separated files (`_linux.go`, `_android.go`, `_windows.go`, `_darwin.go`, `_fallback.go`). Android implies Linux in Go, so Linux files need `//go:build linux && !android`.
- **Agent binary size**: Must stay under 5MB. gRPC was removed for this reason (ADR-001). UPX compression used (ADR-002).
- **Agent OTA flow**: Controller stores artifacts → agent polls via heartbeat → downloads binary → watchdog swaps on exit code 42 → rollback on repeated crashes.
- **Controller DTOs**: Separate DTO classes in `dto/` package for all API request/response types. Entities in `model/` are JPA-annotated.
- **Controller security**: `SecurityConfig.java` defines two auth paths — agent endpoints (permitAll) and org-scoped endpoints (JWT + API key filter).
- **Database migrations**: Flyway in `controller/src/main/resources/db/migration/` (V1 devices, V2 auth/tenancy, V3 OTA).
- **Integration tests**: Agent uses Docker container with systemd + Mosquitto (`//go:build integration`). Controller uses Testcontainers with PostgreSQL.
- **UI API layer**: `lib/api-client.ts` wraps fetch with auth token injection. Domain-specific functions in `lib/api/`.

## Dev Credentials (docker-compose)

PostgreSQL `edgeguardian:edgeguardian-dev`, Keycloak admin `admin:admin`, EMQX `admin:public`, Grafana `admin:admin`.