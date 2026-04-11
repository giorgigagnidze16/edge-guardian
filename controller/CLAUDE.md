# CLAUDE.md — EdgeGuardian Controller

Instructions for Claude Code when working in the `controller/` directory.

## Build & Test

```bash
./gradlew build            # Full build + tests (requires Docker)
./gradlew test             # Tests only (Testcontainers → real PostgreSQL)
./gradlew compileJava      # Fast compile check
./gradlew bootRun          # Run locally (needs infra from deployments/docker-compose.yml)
```

Infrastructure must be running for `bootRun`: `docker compose -f ../deployments/docker-compose.yml up -d`

## Project Structure

```
src/main/java/com/edgeguardian/controller/
├── api/              # REST controllers (9 classes)
├── config/           # Spring @Configuration + @ConfigurationProperties
├── dto/              # Request/response records — never expose entities directly
├── model/            # JPA @Entity classes + enums
├── mqtt/             # MQTT listeners (7) + CommandPublisher + MqttTopics utility
├── repository/       # Spring Data JPA interfaces (18)
├── security/         # Auth filters (JWT, API key, device token), OrgSecurity, TenantPrincipal
├── service/          # Business logic (13 services)
└── EdgeGuardianControllerApplication.java

src/main/resources/
├── application.yaml                  # Base config (port 8443, virtual threads)
├── application-{profile}.yaml        # Per-concern configs (db, mqtt, security, ca, storage, logging, monitoring, local)
└── db/migration/V{1-6}__*.sql        # Flyway migrations

src/test/java/
├── AbstractIntegrationTest.java      # Testcontainers base (TimescaleDB)
└── service/
    ├── CertificateServiceTest.java   # Cert lifecycle: approval, rejection, compromise detection, renewal
    └── DeviceRegistryTest.java       # Device registration and re-registration
```

## Key Conventions

### Layering
Controller -> Service -> Repository. Controllers never touch repositories directly. MQTT listeners follow the same rule — they delegate to services.

### DTOs
All API responses use records from the `dto/` package. Entities in `model/` are JPA-annotated and must not leak into API responses. Each DTO has a `static from(Entity)` factory method.

### Security
`SecurityConfig.java` defines two auth paths:
- **Agent endpoints** (`/api/v1/agent/**`) — authenticated via `X-Device-Token` header (DeviceTokenAuthFilter)
- **Dashboard endpoints** — JWT (Keycloak OAuth2) or `X-API-Key` header (ApiKeyAuthenticationFilter)
- Authorization uses `@PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ROLE')")` with hierarchy: VIEWER < OPERATOR < ADMIN < OWNER

### MQTT
- All listeners are in the `mqtt/` package, subscribe in `@PostConstruct`
- Topic format: `{topicRoot}/device/{deviceId}/{suffix}` — `topicRoot` defaults to `edgeguardian`
- Device ID is extracted from the topic path via `MqttTopics.extractDeviceId(topic)`
- QoS constants: `QOS_RELIABLE (1)` for commands/certs/enrollment, `QOS_BEST_EFFORT (0)` for telemetry/heartbeats/logs
- The controller connects as MQTT user `controller` with full `edgeguardian/#` ACL access

### Certificate Security Model
Compromise detection applies to ALL non-renewal cert requests (INITIAL and MANIFEST):
- Device has valid cert + non-renewal request → BLOCKED, all certs revoked, device SUSPENDED
- No valid cert + MANIFEST → auto-approved
- No valid cert + INITIAL → PENDING (manual approval)
- RENEWAL with valid currentSerial → auto-approved (old cert rotated)

Admin must revoke old certs and un-suspend the device before it can re-request.

### Multi-Tenancy
Every resource is scoped to an `organizationId`. The `TenantPrincipal` record carries `(organizationId, userId, identity, orgRole)` through the security context. Auto-created personal org on first Keycloak login.

### Database
- PostgreSQL 16 with TimescaleDB extension for telemetry hypertable
- Flyway migrations in `db/migration/` (V1 through V6)
- Device telemetry uses time-series compression and retention policies
- Unique constraints: `devices.device_id`, `users.keycloak_id`, `users.email`, `organizations.slug`, `device_tokens.device_id`

## What NOT to Do

- Do not add `@Transactional` to controllers — it belongs on service methods
- Do not return JPA entities from REST endpoints — use DTOs
- Do not bypass the service layer from MQTT listeners
- Do not hardcode topic strings — use `topicRoot` + `MqttTopics` utility
- Do not add new Spring profiles without a corresponding `application-{name}.yaml`
- Do not use `CertRequestType.MANIFEST` to skip security checks — compromise detection is intentionally applied to all non-renewal types
