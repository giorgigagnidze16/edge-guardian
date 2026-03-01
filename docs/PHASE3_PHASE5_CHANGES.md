# Phase 3 (Security + OTA + Observability) & Phase 5 (UI Dashboard) — Changes

Implementation of Sprints 1-3 covering auth foundation, OTA pipeline, observability, and the full Next.js dashboard.

---

## Sprint 1A: Controller Auth Foundation

### 1. WebFlux → Spring MVC + Virtual Threads

Migrated the controller from reactive Spring WebFlux to blocking Spring MVC with Java 21 virtual threads. This simplifies the code (no `Mono`/`Flux` wrappers) while preserving high concurrency via `spring.threads.virtual.enabled=true`.

**Modified files:**

| File | Change |
|------|--------|
| `controller/build.gradle` | Replaced `spring-boot-starter-webflux` with `spring-boot-starter-web`; added `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`; replaced `reactor-test` with `spring-security-test` |
| `controller/src/main/resources/application.yaml` | Added `spring.threads.virtual.enabled: true` and Keycloak JWT issuer URI |
| `controller/.../api/DeviceController.java` | `Flux<DeviceDto>` → `List<DeviceDto>`, `Mono<DeviceDto>` → `DeviceDto`, `Mono<Void>` → `void` |
| `controller/.../config/WebConfig.java` | `WebFluxConfigurer` → `WebMvcConfigurer`; registers `TenantInterceptor` |
| `controller/.../api/AgentApiControllerTest.java` | `@WebFluxTest`/`WebTestClient` → `@WebMvcTest`/`MockMvc`; added `@WithMockUser` + `csrf()` |
| `controller/src/test/resources/application-test.yaml` | Added JWT issuer URI for test profile |

### 2. Flyway V2: Auth & Tenancy Tables

**New file:** `controller/src/main/resources/db/migration/V2__auth_and_tenancy.sql`

Creates the following tables:

- `organizations` — tenant organizations (id, name, slug, description, timestamps)
- `users` — Keycloak-synced users (keycloak_subject, email, display_name)
- `organization_members` — join table with role (owner/admin/operator/viewer)
- `enrollment_tokens` — one-time device registration tokens with expiry and usage counter
- `api_keys` — org-scoped API keys stored as SHA-256 hashes
- `audit_log` — immutable action log (actor, action, resource_type, resource_id, details JSONB)

Also adds nullable `organization_id` FK columns to `devices` and `device_manifests`.

Uses `VARCHAR` with `CHECK` constraints instead of PostgreSQL `ENUM` types for test portability.

### 3. JPA Entities with Lombok

Added Lombok annotations (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) to all JPA entities.

**Modified entities:** `Device.java`, `DeviceManifestEntity.java`, `DeviceStatus.java` — added Lombok + `organizationId` field.

**New entities:**

| Entity | Description |
|--------|-------------|
| `Organization.java` | Tenant organization |
| `User.java` | Keycloak-synced user (subject UUID, email, display name) |
| `OrgRole.java` | Enum: `owner`, `admin`, `operator`, `viewer` |
| `OrganizationMember.java` | User-org join with role; unique on (org_id, user_id) |
| `EnrollmentToken.java` | Device enrollment token with `isValid()` method |
| `ApiKey.java` | API key with SHA-256 hash storage and `isValid()` method |
| `AuditLog.java` | Immutable audit trail entry |

### 4. Spring Security + Tenant Context

**New files:**

| File | Description |
|------|-------------|
| `SecurityConfig.java` | Filter chain: CSRF disabled, stateless sessions, API key filter before `UsernamePasswordAuthenticationFilter`, `/api/v1/agent/**` open, `/api/v1/**` requires JWT, OAuth2 resource server |
| `ApiKeyAuthenticationFilter.java` | `OncePerRequestFilter` — reads `X-API-Key` header, SHA-256 hashes it, looks up in DB |
| `TenantContext.java` | `ThreadLocal` holder for current request's org ID and user ID |
| `TenantInterceptor.java` | MVC `HandlerInterceptor` — extracts user from Keycloak JWT, resolves org, populates `TenantContext` |

**New repositories:** `UserRepository`, `ApiKeyRepository`, `OrganizationRepository`, `OrganizationMemberRepository`, `EnrollmentTokenRepository`, `AuditLogRepository`

### 5. Services

| Service | Description |
|---------|-------------|
| `UserService` | Syncs user from Keycloak JWT on first login; auto-creates personal organization |
| `OrganizationService` | Org CRUD, member management, role-based permission checks |
| `EnrollmentService` | Creates/revokes enrollment tokens; `POST /api/v1/agent/enroll` validates token and registers device |
| `ApiKeyService` | Creates API keys (returns raw key once, stores SHA-256 hash), revokes keys |
| `AuditService` | Logs all mutations as immutable audit entries |
| `DeviceHealthScheduler` | `@Scheduled(fixedRate=60000)` — marks devices `OFFLINE` after 5 minutes without heartbeat |

### 6. REST Endpoints + DTOs

**New controllers:**

| Controller | Routes |
|------------|--------|
| `MeController` | `GET /api/v1/me` — user profile + org memberships |
| `OrganizationController` | `POST/GET/PUT/DELETE /api/v1/organizations`, `POST/GET/DELETE .../members` |
| `EnrollmentTokenController` | `POST/GET /api/v1/organizations/{orgId}/enrollment-tokens` |
| `ApiKeyController` | `POST/GET/DELETE /api/v1/organizations/{orgId}/api-keys` |

**New file:** `GlobalExceptionHandler.java` — converts exceptions to RFC 7807 `ProblemDetail` responses.

**New DTOs (all Java records):** `UserDto`, `OrganizationDto`, `CreateOrganizationRequest`, `UpdateOrganizationRequest`, `MemberDto`, `AddMemberRequest`, `CreateEnrollmentTokenRequest`, `EnrollmentTokenDto`, `CreateApiKeyRequest`, `ApiKeyDto`, `ApiKeyCreateResponse`, `EnrollDeviceRequest`, `MeResponse`

### 7. Keycloak + Docker Compose

**New file:** `deployments/keycloak/edgeguardian-realm.json` — Keycloak realm with Google + GitHub identity providers, `firstBrokerLogin` flow for email linking, `edgeguardian-ui` OIDC client with PKCE.

**Modified:** `deployments/docker-compose.yml` — added Keycloak 26.0 service on port 9090, sharing PostgreSQL, with realm auto-import.

---

## Sprint 1B: UI Foundation

### Project Setup

| File | Description |
|------|-------------|
| `ui/package.json` | Added next-auth v5, @tanstack/react-query v5, recharts, @monaco-editor/react, lucide-react, @radix-ui primitives, clsx, tailwind-merge, class-variance-authority, date-fns, zod |
| `ui/postcss.config.mjs` | Tailwind CSS v4 via `@tailwindcss/postcss` |
| `ui/app/globals.css` | Tailwind theme with zinc color scheme, CSS variables for light/dark mode |
| `ui/lib/utils.ts` | `cn()` utility (clsx + tailwind-merge) |

### Auth (NextAuth v5)

| File | Description |
|------|-------------|
| `ui/lib/auth.ts` | NextAuth config with KeycloakProvider, JWT callback (stores access_token), session callback |
| `ui/app/api/auth/[...nextauth]/route.ts` | Route handler |
| `ui/middleware.ts` | Protects all routes except `/auth/*` and Next.js internals |
| `ui/types/next-auth.d.ts` | Session/JWT type augmentation for `accessToken` |
| `ui/.env.local` | `AUTH_SECRET`, `KEYCLOAK_*`, `NEXT_PUBLIC_API_URL` |

### API Client Layer

| File | Description |
|------|-------------|
| `ui/lib/api-client.ts` | Fetch wrapper with Bearer token injection, typed `ApiError` |
| `ui/lib/api/devices.ts` | `listDevices`, `getDevice`, `deleteDevice`, `getDeviceCount`, `getDeviceManifest`, `updateDeviceManifest`, `getDeviceLogs` |
| `ui/lib/api/organizations.ts` | `getMe`, `createOrganization`, `getOrganization`, `listMembers`, `addMember`, `listEnrollmentTokens`, `createEnrollmentToken`, `listApiKeys`, `createApiKey` |
| `ui/lib/api/ota.ts` | `listArtifacts`, `createArtifact`, `listDeployments`, `createDeployment` |

### Layout & Components

| File | Description |
|------|-------------|
| `ui/components/providers.tsx` | `QueryClientProvider` + `SessionProvider` |
| `ui/components/sidebar.tsx` | Dashboard nav (Dashboard, Devices, OTA, Audit, Settings) |
| `ui/components/user-menu.tsx` | User avatar + sign out |
| `ui/components/org-switcher.tsx` | Organization dropdown |
| `ui/app/layout.tsx` | Root layout with `<Providers>` |
| `ui/app/(dashboard)/layout.tsx` | Sidebar + topbar shell |
| `ui/app/auth/login/page.tsx` | Login page with Keycloak button |

### shadcn/ui Components

`button.tsx`, `card.tsx`, `badge.tsx`, `skeleton.tsx`, `input.tsx`, `table.tsx`

---

## Sprint 2A: OTA Pipeline

### Controller Side

**New file:** `controller/src/main/resources/db/migration/V3__ota.sql`

Creates tables:
- `ota_artifacts` — name, version, arch, size, sha256, ed25519_sig, s3_key, org FK
- `ota_deployments` — strategy (rolling/canary/immediate), state, label selector JSONB, artifact FK
- `deployment_device_status` — per-device OTA state (pending/downloading/verifying/applying/completed/failed/rolled_back)

**New entities:** `OtaArtifact`, `OtaDeployment`, `DeploymentDeviceStatus`

**New:** `DeviceCommand.java` — sealed interface with records: `OtaUpdate`, `Restart`, `Exec`, `VpnConfigure`

**New:** `OTAService` — artifact upload, deployment creation with label-based device targeting, MQTT command publishing

**New:** `OTAController` — REST endpoints under `/api/v1/organizations/{orgId}/ota`

**New DTOs:** `OtaArtifactDto`, `CreateOtaArtifactRequest`, `OtaDeploymentDto`, `CreateOtaDeploymentRequest`

### Agent Side

| File | Description |
|------|-------------|
| `agent/internal/ota/updater.go` | **NEW** — `Download()` (streaming HTTP + SHA-256 verify), `VerifyEd25519()`, `Apply()` (chmod + exit code 42 for watchdog swap) |
| `agent/internal/commands/dispatcher.go` | **NEW** — Routes commands by type to handlers; built-in `ota_update` and `restart` handlers |
| `agent/cmd/agent/main.go` | **MODIFIED** — Imports `commands` + `ota` packages, creates OTA updater + dispatcher, replaces MQTT/heartbeat stub handlers with `dispatcher.Dispatch()` |
| `agent/internal/config/config.go` | **MODIFIED** — Added `OTAConfig`, `AuthConfig`, `LogForwardingConfig` structs |
| `agent/internal/comms/http_client.go` | **MODIFIED** — Added `authToken` field, `SetAuthToken()`, `Enroll()` method, auth header injection in `doJSON` |
| `agent/internal/model/manifest.go` | **MODIFIED** — Added `EnrollRequest`, `OTAStatus` types |

### Watchdog

| File | Description |
|------|-------------|
| `agent/cmd/watchdog/main.go` | **MODIFIED** — Exit code 42 triggers `swapBinary()` (rename staging → active); rollback after 3 crashes in 5-minute window; health check IPC |
| `agent/cmd/watchdog/defaults_*.go` | **MODIFIED** — Added `defaultDataDir` constant to all platform files (linux, darwin, windows, fallback) |

---

## Sprint 2B: Core Dashboard Pages

### Shared Components

| Component | File | Description |
|-----------|------|-------------|
| MetricCard | `ui/components/metric-card.tsx` | Reusable card with icon, value, and description |
| StateBadge | `ui/components/state-badge.tsx` | Color-coded badge mapping device states (ONLINE=green, OFFLINE=gray, DEGRADED=yellow) |
| DataTable | `ui/components/data-table.tsx` | Generic typed `DataTable<T>` with columns, search, empty state, loading skeletons, row click |
| EmptyState | `ui/components/empty-state.tsx` | Centered placeholder with icon, title, description, and action slot |
| LoadingSkeleton | `ui/components/loading-skeleton.tsx` | `MetricCardSkeleton`, `TableRowSkeleton`, `DeviceDetailSkeleton` |

### Pages

| Route | File | Description |
|-------|------|-------------|
| `/` | `ui/app/(dashboard)/page.tsx` | **MODIFIED** — Fleet overview with live MetricCards (total, online, degraded) fetched via TanStack Query |
| `/devices` | `ui/app/(dashboard)/devices/page.tsx` | Device list with DataTable: hostname, state badge, arch, version, last heartbeat, labels; row click navigates to detail |
| `/devices/[id]` | `ui/app/(dashboard)/devices/[id]/page.tsx` | Device detail: CPU/memory/disk/temp MetricCards, device info card, Recharts area chart, labels, manifest preview, links to logs and manifest editor |
| `/devices/[id]/manifest` | `ui/app/(dashboard)/devices/[id]/manifest/page.tsx` | Monaco YAML editor with save mutation, success/error feedback |

---

## Sprint 3A: Observability

### EMQX (Replaces Mosquitto)

| File | Description |
|------|-------------|
| `deployments/docker-compose.yml` | **MODIFIED** — Replaced `mosquitto` service with `emqx/emqx:5.8`; ports 1883 (MQTT), 8083 (WebSocket), 18083 (EMQX Dashboard) |
| `deployments/emqx/acl.conf` | **NEW** — Device-scoped pub/sub ACL: devices publish to own telemetry/status/logs/ota topics, subscribe to own command topic; controller gets full `edgeguardian/#` access |

### Loki + Grafana

| File | Description |
|------|-------------|
| `deployments/loki/loki.yaml` | **NEW** — Loki config: single-binary mode, filesystem TSDB storage, in-memory ring, HTTP on port 3100 |
| `deployments/grafana/provisioning/datasources/loki.yaml` | **NEW** — Grafana datasource provisioning pointing to Loki |
| `deployments/docker-compose.yml` | Added `loki` (grafana/loki:3.3.0 on port 3100) and `grafana` (grafana/grafana:11.4.0 on port 3000 with Keycloak SSO) services |

### Agent Log Forwarding

| File | Description |
|------|-------------|
| `agent/internal/logfwd/forwarder.go` | **NEW** — Ring buffer log forwarder: collects `Entry` structs, periodically flushes batches as JSON via a `Publisher` function (MQTT) |
| `agent/internal/logfwd/file_reader.go` | **NEW** — Cross-platform file tailer: polls a log file for new lines, pushes to `Forwarder` |
| `agent/internal/logfwd/journald_linux.go` | **NEW** — Linux-only: runs `journalctl --follow` subprocess, streams entries to `Forwarder` |

### Controller Log Pipeline

| File | Description |
|------|-------------|
| `controller/.../mqtt/LogIngestionListener.java` | **NEW** — Subscribes to `{root}/device/+/logs` MQTT topic, forwards batches to Loki via `LogService` |
| `controller/.../service/LogService.java` | **NEW** — HTTP client for Loki: `pushToLoki()` converts MQTT log entries to Loki push format; `queryLogs()` proxies LogQL queries |
| `controller/.../api/DeviceController.java` | **MODIFIED** — Added `GET /api/v1/devices/{deviceId}/logs` endpoint proxying Loki with time range, level, and search params |

### OpenTelemetry

| File | Change |
|------|--------|
| `controller/build.gradle` | Added `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` |
| `controller/src/main/resources/application.yaml` | Added `management.tracing.sampling.probability: 1.0` and actuator endpoint exposure (health, info, metrics, prometheus) |

---

## Sprint 3B: Advanced UI Pages

### OTA Management

| Route | File | Description |
|-------|------|-------------|
| `/ota` | `ui/app/(dashboard)/ota/page.tsx` | Artifact list (name, version, arch, size) + deployment list (strategy, state, label selector) in two DataTables; upload and deploy buttons |

### Log Viewer

| Route | File | Description |
|-------|------|-------------|
| `/devices/[id]/logs` | `ui/app/(dashboard)/devices/[id]/logs/page.tsx` | Real-time log viewer: 10s auto-refresh, level filter buttons (All/ERROR/WARN/INFO/DEBUG), text search, color-coded log lines from Loki |

### Settings

| Route | File | Description |
|-------|------|-------------|
| `/settings` | `ui/app/(dashboard)/settings/page.tsx` | Org info card, members DataTable, enrollment token management (create + list), API key management (create with copy-once modal + list) |

### Audit Log

| Route | File | Description |
|-------|------|-------------|
| `/audit` | `ui/app/(dashboard)/audit/page.tsx` | Timeline view of audit entries with action/resource badges, user attribution, relative timestamps, and text search filter |

---

## Verification

All three components pass their respective checks:

```bash
# Agent (Go)
cd agent && go build ./...    # Compiles cleanly
cd agent && go test ./...     # All unit tests pass

# Controller (Java)
cd controller && ./gradlew test   # 19 tests pass (unit + integration with Testcontainers PostgreSQL)

# UI (Next.js)
cd ui && pnpm build    # 9 pages compile, type-checked, production bundle built
```

### Build Output

```
Route (app)                                 Size  First Load JS
┌ ○ /                                    2.65 kB         124 kB
├ ○ /audit                               6.53 kB         131 kB
├ ○ /auth/login                          2.64 kB         115 kB
├ ○ /devices                             4.03 kB         129 kB
├ ƒ /devices/[id]                         108 kB         233 kB
├ ƒ /devices/[id]/logs                   4.44 kB         125 kB
├ ƒ /devices/[id]/manifest               9.47 kB         130 kB
├ ○ /ota                                 5.31 kB         130 kB
└ ○ /settings                            7.94 kB         133 kB
```

---

## File Counts

| Component | New | Modified | Total |
|-----------|-----|----------|-------|
| Controller (Java + resources) | ~40 | ~9 | ~49 |
| Agent (Go) | ~8 | ~9 | ~17 |
| UI (TypeScript/TSX) | ~32 | ~2 | ~34 |
| Deployments (config) | 4 | 1 | 5 |
| **Total** | **~84** | **~21** | **~105** |

---

## Key Architectural Decisions

1. **WebFlux → MVC with virtual threads** — Simpler code without reactive wrappers; virtual threads provide equivalent concurrency for I/O-bound operations.

2. **VARCHAR + CHECK vs PostgreSQL ENUM** — Used `VARCHAR` columns with `CHECK` constraints for role/state fields instead of PostgreSQL `ENUM` types, ensuring Testcontainers PostgreSQL compatibility.

3. **Sealed interface for DeviceCommand** — Java 21 sealed interface with record implementations (`OtaUpdate`, `Restart`, `Exec`, `VpnConfigure`) provides type-safe command dispatch.

4. **Exit code 42 for OTA** — Agent exits with code 42 to signal the watchdog to swap the staged binary, avoiding in-process self-replacement complexity.

5. **Loki over direct DB logging** — Device logs flow through MQTT → controller → Loki, leveraging Grafana's existing LogQL query engine rather than building custom log storage.

6. **EMQX over Mosquitto** — EMQX provides built-in JWT authentication, fine-grained ACL, a management dashboard, and better scalability for production deployments.
