# CLAUDE.md - EdgeGuardian Controller

Instructions for Claude Code when working in the `controller/` directory.

## Build & Test

```bash
./gradlew build            # Full build + tests (requires Docker)
./gradlew test             # Tests only (Testcontainers → real PostgreSQL)
./gradlew compileJava      # Fast compile check
./gradlew bootRun          # Run locally (needs infra in a minikube cluster - see ../scripts/install.sh)
```

Infrastructure runs in Kubernetes via Helm. For iterative backend work, bring up the full stack once
with `../scripts/install.sh`, then either port-forward the dependencies you need
(`kubectl -n edgeguardian port-forward svc/postgres 5432:5432`, etc.) or re-deploy the controller
image into minikube by rebuilding with `./gradlew bootBuildImage` and rolling the deployment.

## Project Structure

```
src/main/java/com/edgeguardian/controller/
├── api/              # REST controllers (10 classes)
├── config/           # Spring @Configuration + @ConfigurationProperties (incl. SecurityConfig)
├── dto/              # Request/response records - never expose entities directly
├── model/            # JPA @Entity classes + enums
├── mqtt/             # MQTT listeners (7) + CommandPublisher + MqttTopics utility
├── repository/       # Spring Data JPA interfaces (19)
├── security/         # Auth filters (API key, device token, JWT converter), OrgSecurity, TenantPrincipal
├── service/          # Business logic (19 top-level services + pki/ + result/ subpackages)
└── EdgeGuardianControllerApplication.java

src/main/resources/
├── application.yaml                  # Base config (port 8443, virtual threads)
├── application-{profile}.yaml        # Per-concern configs (db, mqtt, security, ca, storage, logging, monitoring, local)
└── db/migration/V{1-6}__*.sql        # Flyway migrations (core, ota, commands, pki, telemetry, seed_default_org)

src/test/java/com/edgeguardian/controller/
├── AbstractIntegrationTest.java      # Testcontainers base (TimescaleDB)
└── service/
    ├── CertificateServiceTest.java       # Cert lifecycle: approval, rejection, compromise detection, renewal
    ├── CrlServiceIT.java                 # CRL publication + EMQX kickout on revocation
    ├── CrossTenantAccessTest.java        # Org-scoped isolation (findByIdForOrganization returns 404 cross-tenant)
    ├── DeviceLifecycleServiceIT.java     # Lifecycle transitions against real DB
    ├── DeviceLifecycleServiceTest.java   # Lifecycle unit tests
    ├── DeviceRegistryTenancyTest.java    # Tenancy enforcement on registration
    ├── DeviceRegistryTest.java           # Device registration and re-registration
    └── EnrollmentWithCsrIT.java          # Agent enroll flow: bootstrap → CSR → signed cert
```

## Key Conventions

### Layering
Controller -> Service -> Repository. Controllers never touch repositories directly. MQTT listeners follow the same rule - they delegate to services.

### DTOs
All API responses use records from the `dto/` package. Entities in `model/` are JPA-annotated and must not leak into API responses. Each DTO has a `static from(Entity)` factory method.

### Security
`config/SecurityConfig.java` defines the HTTP auth model (agent data-plane comms are MQTT-only):
- **`permitAll`** - `/actuator/health/**`, `/actuator/info`, `/api/v1/agent/enroll` (bootstrap-credentials auth happens at the broker), and the public PKI endpoints: `/api/v1/pki/crl/**`, `/api/v1/pki/ca-bundle`, `/api/v1/pki/broker-ca`
- **Authenticated** - everything else under `/api/v1/**` via JWT (Keycloak OAuth2) or `X-API-Key` header (ApiKeyAuthenticationFilter). `DeviceTokenAuthFilter` is registered but used only by legacy callers.
- **`denyAll`** - any other path. Intentional backstop: new routes outside `/api/v1/` must opt in to a rule.
- Authorization uses `@PreAuthorize("@orgSecurity.hasMinRole(authentication, 'ROLE')")` with hierarchy: VIEWER < OPERATOR < ADMIN < OWNER. Org-scoped repositories expose `findByIdForOrganization(...)` - cross-tenant access returns 404 rather than 403.

### MQTT
- All listeners are in the `mqtt/` package, subscribe in `@PostConstruct`
- Topic format: `{topicRoot}/device/{deviceId}/{suffix}` - `topicRoot` defaults to `edgeguardian`
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
- PostgreSQL 16 with TimescaleDB extension for the telemetry hypertable
- Flyway migrations in `db/migration/`: `V1__core` (devices, auth/tenancy, API keys, enrollment tokens), `V2__ota`, `V3__commands`, `V4__pki`, `V5__telemetry` (hypertable + compression + retention), `V6__seed_default_org`
- Unique constraints: `devices.device_id`, `users.keycloak_id`, `users.email`, `organizations.slug`, `device_tokens.device_id`

## What NOT to Do

- Do not add `@Transactional` to controllers - it belongs on service methods
- Do not return JPA entities from REST endpoints - use DTOs
- Do not bypass the service layer from MQTT listeners
- Do not hardcode topic strings - use `topicRoot` + `MqttTopics` utility
- Do not add new Spring profiles without a corresponding `application-{name}.yaml`
- Do not use `CertRequestType.MANIFEST` to skip security checks - compromise detection is intentionally applied to all non-renewal types
