I now have a complete picture of the entire codebase. The UI `components/` and `lib/` directories are empty scaffolds. Let me now produce the comprehensive implementation plan.

---

# EdgeGuardian Phase 3 + Phase 5 Implementation Plan

## Codebase Audit Summary

Having read every source file in the project, here is what exists and what needs to change.

**Current state, precisely:**
- Go agent: 302-line `main.go`, working reconciler with file/service plugins, BoltDB offline storage, HTTP client with retry, MQTT client with offline queue. Command handler at line 120-126 is a stub that only logs. Heartbeat pending commands at lines 262-268 also just logged. No auth headers on any HTTP request.
- Controller: Spring Boot 3.4.3, Java 21, WebFlux starter but blocking JPA (antipattern). Single service (`DeviceRegistry`), two controllers (`AgentApiController`, `DeviceController`). `DeviceController` returns `Flux`/`Mono` wrappers around blocking JPA calls. Lombok on classpath but unused. DTOs are Java records with `Map<String, Object>` for loosely-typed fields. `CommandPublisher` exists but is never called from any code path. No Spring Security. No scheduled tasks. No OFFLINE detection.
- Database: Two tables (`devices`, `device_manifests`). No tenant/org/user tables. JSONB labels column has GIN index.
- Infrastructure: docker-compose with postgres:16-alpine and eclipse-mosquitto:2.
- UI: Next.js 15 + React 19 scaffold with a single placeholder page. Empty `components/` and `lib/` directories.

---

## WORKSTREAM 1: Phase 3A -- Security and Auth Foundation (Week 1-2)

### 1.1 Keycloak Docker Setup

**File: `deployments/docker-compose.yml`** -- Add Keycloak and update Mosquitto replacement.

Add Keycloak service:
```yaml
  keycloak:
    image: quay.io/keycloak/keycloak:26.0
    container_name: edgeguardian-keycloak
    command: start-dev --import-realm
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/edgeguardian
      KC_DB_SCHEMA: keycloak
      KC_DB_USERNAME: edgeguardian
      KC_DB_PASSWORD: edgeguardian-dev
      KC_HOSTNAME: localhost
      KC_HTTP_PORT: 8080
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports:
      - "8080:8080"
    volumes:
      - ./keycloak/edgeguardian-realm.json:/opt/keycloak/data/import/edgeguardian-realm.json
    depends_on:
      postgres:
        condition: service_healthy
```

**File: `deployments/keycloak/edgeguardian-realm.json`** -- Realm export JSON defining:
- Realm name: `edgeguardian`
- Client: `edgeguardian-controller` (confidential, service account enabled), `edgeguardian-dashboard` (public SPA client, PKCE)
- Identity providers: Google (social), GitHub (social) -- configured with placeholder client IDs (user fills in real ones)
- Account linking: `firstBrokerLogin` authentication flow with `idp-auto-link` execution that links accounts by verified email
- Client scopes: `openid`, `profile`, `email`, plus custom `edgeguardian-api` scope
- Realm roles: none (RBAC managed in application DB, not Keycloak)
- Protocol mapper on `edgeguardian-controller` client: include `sub` (keycloak_id), `email`, `preferred_username`, `given_name`, `family_name` in the access token

### 1.2 Switch from WebFlux to Spring MVC + Virtual Threads

**File: `controller/build.gradle`** -- Replace dependencies:
```groovy
// REMOVE:
implementation 'org.springframework.boot:spring-boot-starter-webflux'
// ADD:
implementation 'org.springframework.boot:spring-boot-starter-web'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
implementation 'org.springframework.boot:spring-boot-starter-security'
```

Also replace test dependencies:
```groovy
// REMOVE:
testImplementation 'io.projectreactor:reactor-test'
// ADD:
testImplementation 'org.springframework.security:spring-security-test'
```

**File: `controller/src/main/resources/application.yaml`** -- Add virtual threads and Keycloak config:
```yaml
spring:
  threads:
    virtual:
      enabled: true

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/edgeguardian
          jwk-set-uri: http://localhost:8080/realms/edgeguardian/protocol/openid-connect/certs
```

**Files to modify for Spring MVC migration:**

`controller/src/main/java/com/edgeguardian/controller/api/DeviceController.java` -- Remove Flux/Mono, return plain Java types:
```java
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
@Slf4j
public class DeviceController {
    private final DeviceRegistry registry;

    @GetMapping
    public List<DeviceDto> listDevices() {
        return registry.findAll().stream().map(DeviceDto::from).toList();
    }

    @GetMapping("/{deviceId}")
    public DeviceDto getDevice(@PathVariable String deviceId) {
        return registry.findById(deviceId)
                .map(DeviceDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDevice(@PathVariable String deviceId) {
        if (!registry.remove(deviceId)) {
            throw new ResourceNotFoundException("Device", deviceId);
        }
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/config/WebConfig.java` -- Change from `WebFluxConfigurer` to `WebMvcConfigurer`:
```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

**Tests to modify:** `AgentApiControllerTest.java` -- Change from `@WebFluxTest` + `WebTestClient` to `@WebMvcTest` + `MockMvc`.

### 1.3 Database Migration V2: Auth and Tenancy

**File: `controller/src/main/resources/db/migration/V2__auth_and_tenancy.sql`**

```sql
-- V2__auth_and_tenancy.sql
-- Multi-tenancy, users, RBAC, enrollment tokens, API keys, audit log.

-- Organizations (tenants).
CREATE TABLE organizations (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(128) NOT NULL UNIQUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Users linked to Keycloak.
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    keycloak_id     VARCHAR(255) NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    display_name    VARCHAR(255),
    avatar_url      VARCHAR(512),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Organization membership with roles.
CREATE TYPE org_role AS ENUM ('ADMIN', 'OPERATOR', 'VIEWER');

CREATE TABLE organization_members (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            org_role NOT NULL DEFAULT 'VIEWER',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_member UNIQUE (organization_id, user_id)
);

-- Enrollment tokens for device registration.
CREATE TABLE enrollment_tokens (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    token           VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    max_uses        INT,
    use_count       INT NOT NULL DEFAULT 0,
    labels          JSONB NOT NULL DEFAULT '{}',
    expires_at      TIMESTAMP WITH TIME ZONE,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- API keys for programmatic access.
CREATE TABLE api_keys (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    key_prefix      VARCHAR(8) NOT NULL,
    key_hash        VARCHAR(128) NOT NULL,
    scopes          TEXT[] NOT NULL DEFAULT '{}',
    expires_at      TIMESTAMP WITH TIME ZONE,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    last_used_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_api_keys_hash ON api_keys(key_hash);

-- Audit log.
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT REFERENCES organizations(id) ON DELETE SET NULL,
    user_id         BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action          VARCHAR(128) NOT NULL,
    resource_type   VARCHAR(64) NOT NULL,
    resource_id     VARCHAR(255),
    details         JSONB,
    ip_address      VARCHAR(45),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_org_time ON audit_log(organization_id, created_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log(action);

-- Add organization_id to existing tables.
ALTER TABLE devices ADD COLUMN organization_id BIGINT REFERENCES organizations(id);
ALTER TABLE device_manifests ADD COLUMN organization_id BIGINT REFERENCES organizations(id);

-- Create indexes for tenant-scoped queries.
CREATE INDEX idx_devices_org ON devices(organization_id);
CREATE INDEX idx_device_manifests_org ON device_manifests(organization_id);
CREATE INDEX idx_enrollment_tokens_org ON enrollment_tokens(organization_id);
CREATE INDEX idx_org_members_user ON organization_members(user_id);
```

Note: `organization_id` on `devices` and `device_manifests` is initially nullable to support the existing data. A subsequent data migration or application-level default org creation will populate these.

### 1.4 JPA Entities with Lombok

**New files to create:**

`controller/src/main/java/com/edgeguardian/controller/model/Organization.java`:
```java
@Entity
@Table(name = "organizations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        var now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/model/User.java`:
```java
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "keycloak_id", nullable = false, unique = true)
    private String keycloakId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/model/OrgRole.java`:
```java
public enum OrgRole {
    ADMIN, OPERATOR, VIEWER
}
```

`controller/src/main/java/com/edgeguardian/controller/model/OrganizationMember.java`:
```java
@Entity
@Table(name = "organization_members")
@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class OrganizationMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    private OrgRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/model/EnrollmentToken.java`:
```java
@Entity
@Table(name = "enrollment_tokens")
@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class EnrollmentToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private String name;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> labels;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isValid() {
        if (revoked) return false;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (maxUses != null && useCount >= maxUses) return false;
        return true;
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/model/ApiKey.java`:
```java
@Entity
@Table(name = "api_keys")
@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class ApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "scopes", columnDefinition = "text[]")
    private String[] scopes;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/model/AuditLog.java`:
```java
@Entity
@Table(name = "audit_log")
@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
```

**Refactor existing entities:** Add `@Data` to `Device.java` and `DeviceManifestEntity.java`, remove all manual getters/setters. Add `@ManyToOne` for `organization` field to both. Add `@Data` to `DeviceStatus.java`.

### 1.5 Repositories

New repository files under `controller/src/main/java/com/edgeguardian/controller/repository/`:

- `OrganizationRepository.java` -- `findBySlug(String slug)`, `Optional<Organization>`
- `UserRepository.java` -- `findByKeycloakId(String keycloakId)`, `findByEmail(String email)`
- `OrganizationMemberRepository.java` -- `findByOrganizationIdAndUserId(Long orgId, Long userId)`, `findByUserId(Long userId)`, `findByOrganizationId(Long orgId)`
- `EnrollmentTokenRepository.java` -- `findByToken(String token)`, `findByOrganizationId(Long orgId)`
- `ApiKeyRepository.java` -- `findByKeyHash(String keyHash)`, `findByOrganizationId(Long orgId)`
- `AuditLogRepository.java` -- extends `JpaRepository` plus `findByOrganizationIdOrderByCreatedAtDesc(Long orgId, Pageable pageable)`

**Modify existing repositories:**
- `DeviceRepository.java` -- Add `findByOrganizationId(Long orgId)`, `Page<Device> findByOrganizationId(Long orgId, Pageable pageable)`, `findByOrganizationIdAndDeviceId(Long orgId, String deviceId)`
- `DeviceManifestRepository.java` -- Add `findByOrganizationIdAndDeviceId(Long orgId, String deviceId)`

### 1.6 Security Configuration

`controller/src/main/java/com/edgeguardian/controller/security/SecurityConfig.java`:
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ApiKeyAuthenticationFilter apiKeyFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/agent/enroll").permitAll()
                .requestMatchers("/api/v1/agent/**").hasAuthority("SCOPE_device")
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
            );
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/security/ApiKeyAuthenticationFilter.java`:
A filter that checks for `Authorization: Bearer egk_...` (API key prefix), hashes the key, looks it up in the DB, and creates a `UsernamePasswordAuthenticationToken` with the API key's scopes and org context.

`controller/src/main/java/com/edgeguardian/controller/security/TenantContext.java`:
A `@RequestScope` bean or `ThreadLocal` holder for the current organization ID, populated by a filter/interceptor after JWT decoding. Pattern:
```java
public record TenantContext(Long organizationId, Long userId, OrgRole role) {}
```

`controller/src/main/java/com/edgeguardian/controller/security/TenantInterceptor.java`:
A `HandlerInterceptor` that reads the `X-Organization-Id` header (or path parameter), verifies the authenticated user is a member of that org, and populates the `TenantContext`.

### 1.7 Services

`controller/src/main/java/com/edgeguardian/controller/service/UserService.java`:
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;
    private final OrganizationService organizationService;

    @Transactional
    public User syncFromJwt(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("preferred_username");

        return userRepository.findByKeycloakId(keycloakId)
                .map(existing -> {
                    existing.setEmail(email);
                    existing.setDisplayName(name);
                    return userRepository.save(existing);
                })
                .orElseGet(() -> {
                    var user = User.builder()
                            .keycloakId(keycloakId)
                            .email(email)
                            .displayName(name)
                            .build();
                    user = userRepository.save(user);
                    // Auto-create personal org on first login
                    organizationService.createDefaultOrg(user);
                    return user;
                });
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/service/OrganizationService.java`:
- `createOrganization(String name, User creator)` -- creates org + ADMIN membership
- `createDefaultOrg(User user)` -- creates `{username}'s Org`
- `addMember(Long orgId, String email, OrgRole role)`
- `removeMember(Long orgId, Long userId)`
- `updateMemberRole(Long orgId, Long userId, OrgRole role)`
- `getOrganizations(Long userId)` -- list orgs user belongs to

`controller/src/main/java/com/edgeguardian/controller/service/EnrollmentService.java`:
- `createToken(Long orgId, String name, Integer maxUses, Instant expiresAt, Map<String, String> labels, User createdBy)`
- `validateToken(String token)` -- returns the `EnrollmentToken` if valid
- `consumeToken(String token)` -- increments `useCount`
- `revokeToken(Long tokenId)`
- `listTokens(Long orgId)`

`controller/src/main/java/com/edgeguardian/controller/service/ApiKeyService.java`:
- `createKey(Long orgId, Long userId, String name, String[] scopes)` -- generates `egk_` prefixed key, hashes with SHA-256, stores hash, returns plaintext once
- `validateKey(String rawKey)` -- hashes and looks up
- `revokeKey(Long keyId)`
- `listKeys(Long orgId)`

`controller/src/main/java/com/edgeguardian/controller/service/AuditService.java`:
- `record(Long orgId, Long userId, String action, String resourceType, String resourceId, Map<String, Object> details, String ipAddress)`
- Integrated into all mutation endpoints via aspect or explicit calls

### 1.8 Device Enrollment Flow

**New endpoint:** `POST /api/v1/agent/enroll` (public, no JWT required)

The agent sends:
```json
{
    "enrollmentToken": "tok_abc123...",
    "deviceId": "rpi-gateway-01",
    "hostname": "raspberrypi",
    "architecture": "arm64",
    "os": "linux",
    "agentVersion": "0.3.0",
    "labels": {"role": "gateway"}
}
```

Controller validates the token, creates the device in the token's organization, generates a device-specific JWT (signed with a symmetric key or the controller's Ed25519 key, short-lived + refresh token), and returns:
```json
{
    "accepted": true,
    "deviceToken": "eyJ...",
    "refreshToken": "eyJ...",
    "tokenExpiresIn": 86400,
    "mqttCredentials": {
        "username": "device:rpi-gateway-01",
        "password": "..."
    }
}
```

The agent stores the `deviceToken` and `refreshToken` in BoltDB (new bucket `auth`) and sends them as `Authorization: Bearer <token>` on all subsequent HTTP requests.

**Agent changes for enrollment:**
- `agent/internal/config/config.go` -- Add `EnrollmentToken string`, `AuthConfig` struct with `TokenPath` field
- `agent/internal/comms/http_client.go` -- Add `SetAuthToken(token string)` method, inject `Authorization` header in `doJSON()`. Add `Enroll()` method.
- `agent/internal/storage/store.go` -- Add `bucketAuth` bucket, `SaveAuthToken(token, refresh string)`, `LoadAuthToken() (token, refresh string, error)`
- `agent/cmd/agent/main.go` -- On startup, check for stored auth token. If none, use enrollment token to call `/api/v1/agent/enroll`. Store returned token. Use token for all subsequent calls. On 401, attempt refresh.

### 1.9 Tenant-Scoped Queries

All device queries must be scoped by `organization_id`. The `DeviceRegistry` service methods gain a `Long orgId` parameter:
```java
@Transactional(readOnly = true)
public Page<Device> findAll(Long orgId, Pageable pageable) {
    return deviceRepository.findByOrganizationId(orgId, pageable);
}
```

The `AgentApiController` endpoints extract org ID from the device JWT token's claims (the device token encodes `orgId` as a claim).

### 1.10 Global Exception Handler

`controller/src/main/java/com/edgeguardian/controller/api/GlobalExceptionHandler.java`:
```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleValidation(ConstraintViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
```

`controller/src/main/java/com/edgeguardian/controller/api/ResourceNotFoundException.java`:
```java
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, String id) {
        super("%s not found: %s".formatted(resource, id));
    }
}
```

### 1.11 Scheduled Task: OFFLINE Detection

`controller/src/main/java/com/edgeguardian/controller/service/DeviceHealthScheduler.java`:
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceHealthScheduler {
    private final DeviceRepository deviceRepository;
    private static final Duration OFFLINE_THRESHOLD = Duration.ofMinutes(5);

    @Scheduled(fixedRate = 60_000)  // every 60 seconds
    @Transactional
    public void markOfflineDevices() {
        Instant cutoff = Instant.now().minus(OFFLINE_THRESHOLD);
        var onlineDevices = deviceRepository.findByState(Device.DeviceState.ONLINE);
        int count = 0;
        for (var device : onlineDevices) {
            if (device.getLastHeartbeat() != null && device.getLastHeartbeat().isBefore(cutoff)) {
                device.setState(Device.DeviceState.OFFLINE);
                deviceRepository.save(device);
                count++;
            }
        }
        if (count > 0) {
            log.info("Marked {} device(s) as OFFLINE", count);
        }
    }
}
```

Add `@EnableScheduling` to `EdgeGuardianControllerApplication.java`.

### 1.12 New REST Endpoints

**File: `controller/src/main/java/com/edgeguardian/controller/api/MeController.java`**
- `GET /api/v1/me` -- Returns current user info + list of organizations

**File: `controller/src/main/java/com/edgeguardian/controller/api/OrganizationController.java`**
- `POST /api/v1/organizations` -- Create organization
- `GET /api/v1/organizations` -- List user's organizations
- `GET /api/v1/organizations/{orgId}` -- Get org details
- `PUT /api/v1/organizations/{orgId}` -- Update org name
- `DELETE /api/v1/organizations/{orgId}` -- Delete org (admin only)
- `GET /api/v1/organizations/{orgId}/members` -- List members
- `POST /api/v1/organizations/{orgId}/members` -- Add member by email
- `PUT /api/v1/organizations/{orgId}/members/{userId}` -- Change role
- `DELETE /api/v1/organizations/{orgId}/members/{userId}` -- Remove member

**File: `controller/src/main/java/com/edgeguardian/controller/api/EnrollmentTokenController.java`**
- `POST /api/v1/organizations/{orgId}/enrollment-tokens`
- `GET /api/v1/organizations/{orgId}/enrollment-tokens`
- `DELETE /api/v1/organizations/{orgId}/enrollment-tokens/{tokenId}`

**File: `controller/src/main/java/com/edgeguardian/controller/api/ApiKeyController.java`**
- `POST /api/v1/organizations/{orgId}/api-keys`
- `GET /api/v1/organizations/{orgId}/api-keys`
- `DELETE /api/v1/organizations/{orgId}/api-keys/{keyId}`

### 1.13 New DTOs (Java Records)

```java
// User & org DTOs
public record UserDto(Long id, String email, String displayName, String avatarUrl) {}
public record MeResponse(UserDto user, List<OrgMembershipDto> organizations) {}
public record OrgMembershipDto(Long organizationId, String name, String slug, OrgRole role) {}

// Organization DTOs
public record CreateOrganizationRequest(@NotBlank String name) {}
public record OrganizationDto(Long id, String name, String slug, Instant createdAt) {}
public record OrganizationMemberDto(Long userId, String email, String displayName, OrgRole role, Instant joinedAt) {}
public record AddMemberRequest(@NotBlank @Email String email, @NotNull OrgRole role) {}
public record UpdateMemberRoleRequest(@NotNull OrgRole role) {}

// Enrollment token DTOs
public record CreateEnrollmentTokenRequest(@NotBlank String name, Integer maxUses,
        Instant expiresAt, Map<String, String> labels) {}
public record EnrollmentTokenDto(Long id, String token, String name, Integer maxUses,
        int useCount, Map<String, String> labels, Instant expiresAt, boolean revoked, Instant createdAt) {}

// API key DTOs
public record CreateApiKeyRequest(@NotBlank String name, List<String> scopes) {}
public record ApiKeyDto(Long id, String name, String keyPrefix, List<String> scopes,
        Instant expiresAt, boolean revoked, Instant lastUsedAt, Instant createdAt) {}
public record ApiKeyCreatedResponse(Long id, String name, String key) {}

// Enrollment (agent-side)
public record AgentEnrollRequest(String enrollmentToken, String deviceId, String hostname,
        String architecture, String os, String agentVersion, Map<String, String> labels) {}
public record AgentEnrollResponse(boolean accepted, String message, String deviceToken,
        String refreshToken, long tokenExpiresIn, MqttCredentials mqttCredentials) {}
public record MqttCredentials(String username, String password) {}

// Audit
public record AuditLogDto(Long id, String action, String resourceType, String resourceId,
        Map<String, Object> details, String userEmail, Instant createdAt) {}
```

### 1.14 Test Approach for Phase 3A

- `SecurityConfigTest` -- Verify unauthenticated requests to `/api/v1/devices` return 401
- `DeviceRegistryTest` -- Add tenant-scoped tests (ensure device from org A not visible to org B)
- `AgentApiControllerTest` -- Migrate to `@WebMvcTest` + `MockMvc`, add `@WithMockUser` or `@WithMockJwtUser`
- `EnrollmentServiceTest` -- Unit test token validation, consumption, expiry
- `ApiKeyServiceTest` -- Unit test key generation, hashing, lookup
- `UserServiceTest` -- Test JWT sync and auto org creation
- Integration tests extend `AbstractIntegrationTest` (already using Testcontainers PostgreSQL)

---

## WORKSTREAM 2: Phase 3B -- OTA Pipeline (Week 3-4)

### 2.1 Database Migration V3: OTA Tables

**File: `controller/src/main/resources/db/migration/V3__ota.sql`**

```sql
-- V3__ota.sql
-- OTA artifact management and deployment tracking.

-- Upload artifacts (binaries, configs, firmware).
CREATE TABLE ota_artifacts (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    version         VARCHAR(64) NOT NULL,
    target_arch     VARCHAR(64),
    artifact_type   VARCHAR(32) NOT NULL DEFAULT 'agent',
    file_size       BIGINT NOT NULL,
    sha256          VARCHAR(64) NOT NULL,
    ed25519_sig     TEXT NOT NULL,
    storage_path    VARCHAR(512) NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}',
    uploaded_by     BIGINT REFERENCES users(id),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_artifact_version UNIQUE (organization_id, name, version, target_arch)
);

-- Deployments target a set of devices.
CREATE TYPE deployment_strategy AS ENUM ('ALL_AT_ONCE', 'ROLLING', 'CANARY');
CREATE TYPE deployment_state AS ENUM ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED');

CREATE TABLE ota_deployments (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    artifact_id     BIGINT NOT NULL REFERENCES ota_artifacts(id),
    name            VARCHAR(255) NOT NULL,
    target_labels   JSONB NOT NULL DEFAULT '{}',
    target_devices  TEXT[],
    strategy        deployment_strategy NOT NULL DEFAULT 'ALL_AT_ONCE',
    state           deployment_state NOT NULL DEFAULT 'PENDING',
    total_devices   INT NOT NULL DEFAULT 0,
    completed_count INT NOT NULL DEFAULT 0,
    failed_count    INT NOT NULL DEFAULT 0,
    created_by      BIGINT REFERENCES users(id),
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Per-device status within a deployment.
CREATE TYPE device_ota_state AS ENUM (
    'PENDING', 'DOWNLOADING', 'VERIFYING', 'INSTALLING',
    'COMPLETED', 'FAILED', 'ROLLED_BACK'
);

CREATE TABLE deployment_device_status (
    id              BIGSERIAL PRIMARY KEY,
    deployment_id   BIGINT NOT NULL REFERENCES ota_deployments(id) ON DELETE CASCADE,
    device_id       VARCHAR(255) NOT NULL,
    state           device_ota_state NOT NULL DEFAULT 'PENDING',
    progress        INT DEFAULT 0,
    error_message   TEXT,
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_deployment_device UNIQUE (deployment_id, device_id)
);

CREATE INDEX idx_ota_artifacts_org ON ota_artifacts(organization_id);
CREATE INDEX idx_ota_deployments_org ON ota_deployments(organization_id);
CREATE INDEX idx_deployment_device_status_deployment ON deployment_device_status(deployment_id);
```

### 2.2 Sealed Interfaces for Command Types and Deployment Strategy

`controller/src/main/java/com/edgeguardian/controller/model/DeviceCommand.java`:
```java
public sealed interface DeviceCommand permits
        DeviceCommand.OtaUpdate,
        DeviceCommand.Restart,
        DeviceCommand.Exec,
        DeviceCommand.VpnConfigure {

    String type();
    Map<String, String> params();

    record OtaUpdate(String artifactUrl, String sha256, String ed25519Sig,
                     String version) implements DeviceCommand {
        @Override public String type() { return "ota_update"; }
        @Override public Map<String, String> params() {
            return Map.of("url", artifactUrl, "sha256", sha256,
                          "signature", ed25519Sig, "version", version);
        }
    }

    record Restart(String reason) implements DeviceCommand {
        @Override public String type() { return "restart"; }
        @Override public Map<String, String> params() {
            return Map.of("reason", reason);
        }
    }

    record Exec(String command, int timeoutSeconds) implements DeviceCommand {
        @Override public String type() { return "exec"; }
        @Override public Map<String, String> params() {
            return Map.of("command", command, "timeout", String.valueOf(timeoutSeconds));
        }
    }

    record VpnConfigure(String action, Map<String, String> config) implements DeviceCommand {
        @Override public String type() { return "vpn_configure"; }
        @Override public Map<String, String> params() {
            var p = new HashMap<>(config);
            p.put("action", action);
            return Map.copyOf(p);
        }
    }
}
```

### 2.3 OTA Service

`controller/src/main/java/com/edgeguardian/controller/service/OTAService.java`:
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OTAService {
    private final OtaArtifactRepository artifactRepository;
    private final OtaDeploymentRepository deploymentRepository;
    private final DeploymentDeviceStatusRepository deviceStatusRepository;
    private final DeviceRepository deviceRepository;
    private final CommandPublisher commandPublisher;
    private final AuditService auditService;

    @Value("${edgeguardian.ota.storage-path:/var/lib/edgeguardian/artifacts}")
    private String storagePath;

    @Transactional
    public OtaArtifact uploadArtifact(Long orgId, String name, String version,
                                       String targetArch, MultipartFile file, User uploadedBy) {
        // 1. Compute SHA-256
        // 2. Generate Ed25519 signature with controller's signing key
        // 3. Save file to storagePath/{orgId}/{name}/{version}/{arch}/artifact
        // 4. Create DB record
        // 5. Audit log
    }

    @Transactional
    public OtaDeployment createDeployment(Long orgId, Long artifactId, String name,
                                           Map<String, String> targetLabels,
                                           DeploymentStrategy strategy, User createdBy) {
        // 1. Resolve target devices by label query (JSONB @> operator)
        // 2. Create deployment record
        // 3. Create device_status rows for each target
        // 4. Publish ota_update commands via MQTT to each device
        // 5. Audit log
    }

    @Transactional
    public void updateDeviceOtaStatus(String deviceId, Long deploymentId,
                                       DeviceOtaState state, Integer progress, String error) {
        // Called by agent heartbeat or dedicated OTA status endpoint
        // Updates deployment_device_status, recalculates deployment totals
    }
}
```

### 2.4 Wire CommandPublisher into OTA Flow

Currently `CommandPublisher.publishCommand()` exists but is never called. The `OTAService.createDeployment()` method will call it for each target device:

```java
for (String deviceId : targetDeviceIds) {
    var cmd = new DeviceCommand.OtaUpdate(artifactDownloadUrl, sha256, signature, version);
    commandPublisher.publishCommand(deviceId, cmd.type(), cmd.params());
}
```

### 2.5 OTA REST Endpoints

`controller/src/main/java/com/edgeguardian/controller/api/OTAController.java`:
- `POST /api/v1/organizations/{orgId}/ota/artifacts` -- Multipart upload
- `GET /api/v1/organizations/{orgId}/ota/artifacts` -- List artifacts
- `GET /api/v1/organizations/{orgId}/ota/artifacts/{id}` -- Get artifact details
- `DELETE /api/v1/organizations/{orgId}/ota/artifacts/{id}` -- Delete artifact
- `POST /api/v1/organizations/{orgId}/ota/deployments` -- Create deployment
- `GET /api/v1/organizations/{orgId}/ota/deployments` -- List deployments
- `GET /api/v1/organizations/{orgId}/ota/deployments/{id}` -- Deployment detail + per-device status
- `POST /api/v1/organizations/{orgId}/ota/deployments/{id}/cancel` -- Cancel deployment

Artifact download endpoint (for agent):
- `GET /api/v1/agent/ota/artifacts/{id}/download` -- Authenticated with device token, streams file

### 2.6 Agent OTA Package

**New directory: `agent/internal/ota/`**

`agent/internal/ota/updater.go`:
```go
package ota

import (
    "context"
    "crypto/ed25519"
    "crypto/sha256"
    "fmt"
    "io"
    "net/http"
    "os"
    "path/filepath"
)

const ExitCodeUpdate = 42

type Config struct {
    StagingDir    string
    PublicKeyPath string
    BinaryPath    string // current agent binary path
}

type Updater struct {
    cfg       Config
    publicKey ed25519.PublicKey
    logger    *zap.Logger
}

func New(cfg Config, logger *zap.Logger) (*Updater, error) {
    // Load Ed25519 public key from file
}

// Download streams the artifact to a temp file, verifying SHA-256.
func (u *Updater) Download(ctx context.Context, url, authToken, expectedSHA string) (string, error) {
    // 1. HTTP GET with streaming
    // 2. io.TeeReader through sha256 hasher
    // 3. Write to staging_dir/artifact.tmp
    // 4. Verify SHA-256 matches
    // 5. Return path to downloaded file
}

// Verify checks the Ed25519 signature against the file contents.
func (u *Updater) Verify(filePath string, signature []byte) error {
    // 1. Read file
    // 2. ed25519.Verify(publicKey, fileContents, signature)
}

// Apply stages the new binary and signals the watchdog.
func (u *Updater) Apply(stagedPath string) error {
    // 1. Copy staged file to staging_dir/agent.new
    // 2. chmod +x
    // 3. Exit with code 42 to signal watchdog
    os.Exit(ExitCodeUpdate)
    return nil
}
```

### 2.7 Command Dispatcher in Agent

**Modify: `agent/cmd/agent/main.go`** -- Replace the stub command handler (lines 120-126) with a real dispatcher:

```go
// Create command dispatcher
cmdDispatcher := commands.NewDispatcher(logger)
cmdDispatcher.Register("ota_update", otaHandler)
cmdDispatcher.Register("restart", restartHandler)
cmdDispatcher.Register("exec", execHandler)

mqttClient.SetCommandHandler(func(cmd model.Command) {
    logger.Info("dispatching command",
        zap.String("id", cmd.ID),
        zap.String("type", cmd.Type),
    )
    if err := cmdDispatcher.Dispatch(ctx, cmd); err != nil {
        logger.Error("command dispatch failed",
            zap.String("id", cmd.ID),
            zap.Error(err),
        )
    }
})
```

**New file: `agent/internal/commands/dispatcher.go`**:
```go
package commands

type Handler func(ctx context.Context, cmd model.Command) error

type Dispatcher struct {
    handlers map[string]Handler
    logger   *zap.Logger
}

func (d *Dispatcher) Register(cmdType string, handler Handler) { ... }
func (d *Dispatcher) Dispatch(ctx context.Context, cmd model.Command) error {
    handler, ok := d.handlers[cmd.Type]
    if !ok {
        return fmt.Errorf("unknown command type: %s", cmd.Type)
    }
    return handler(ctx, cmd)
}
```

### 2.8 Watchdog Upgrade

**Modify: `agent/cmd/watchdog/main.go`** -- Add exit code detection and binary swap:

```go
err := cmd.Run()
if err != nil {
    if exitErr, ok := err.(*exec.ExitError); ok {
        if exitErr.ExitCode() == 42 { // OTA update signal
            fmt.Println("agent requested OTA update, swapping binary")
            if swapErr := swapBinary(); swapErr != nil {
                fmt.Printf("binary swap failed: %v\n", swapErr)
                // Fall through to normal restart
            }
            backoff = initialBackoff
            continue
        }
    }
    // ... existing backoff logic
}
```

Add `swapBinary()` function:
```go
func swapBinary() error {
    newBinary := filepath.Join(defaultStagingDir, "agent.new")
    if _, err := os.Stat(newBinary); os.IsNotExist(err) {
        return fmt.Errorf("no staged binary found at %s", newBinary)
    }
    backup := defaultAgentBinary + ".bak"
    // 1. Rename current -> .bak
    // 2. Rename .new -> current
    // 3. chmod +x
    return nil
}
```

Add crash detection for rollback:
```go
var recentCrashes []time.Time

// In the restart loop, track crashes
recentCrashes = append(recentCrashes, time.Now())
// Keep only last 5 minutes
cutoff := time.Now().Add(-5 * time.Minute)
var filtered []time.Time
for _, t := range recentCrashes {
    if t.After(cutoff) { filtered = append(filtered, t) }
}
recentCrashes = filtered

if len(recentCrashes) >= 3 {
    fmt.Println("3 crashes in 5 minutes, rolling back binary")
    rollbackBinary()
    recentCrashes = nil
}
```

### 2.9 Agent Config Additions

**Modify: `agent/internal/config/config.go`**:
```go
type Config struct {
    // ... existing fields ...
    OTA OTAConfig `yaml:"ota"`
    Auth AuthConfig `yaml:"auth"`
}

type OTAConfig struct {
    StagingDir    string `yaml:"staging_dir"`
    PublicKeyPath string `yaml:"public_key_path"`
}

type AuthConfig struct {
    EnrollmentToken string `yaml:"enrollment_token"`
    TokenStorePath  string `yaml:"token_store_path"` // defaults to data_dir/auth.json
}
```

### 2.10 OTA Status Reporting

**Modify: `agent/internal/model/manifest.go`** -- Add OTA status to heartbeat:
```go
type HeartbeatRequest struct {
    DeviceID     string        `json:"deviceId"`
    AgentVersion string        `json:"agentVersion"`
    Status       *DeviceStatus `json:"status,omitempty"`
    OTAStatus    *OTAStatus    `json:"otaStatus,omitempty"`
    Timestamp    time.Time     `json:"timestamp"`
}

type OTAStatus struct {
    DeploymentID string `json:"deploymentId,omitempty"`
    State        string `json:"state"` // downloading, verifying, installing, completed, failed, rolled_back
    Progress     int    `json:"progress"` // 0-100
    Error        string `json:"error,omitempty"`
}
```

---

## WORKSTREAM 3: Phase 3C -- Observability (Week 5-6)

### 3.1 EMQX Replacing Mosquitto

**Modify: `deployments/docker-compose.yml`** -- Remove `mosquitto` service, add `emqx`:

```yaml
  emqx:
    image: emqx/emqx:5.8
    container_name: edgeguardian-emqx
    environment:
      EMQX_NAME: edgeguardian
      EMQX_HOST: 127.0.0.1
      EMQX_LOADED_PLUGINS: "emqx_auth_jwt"
      EMQX_DASHBOARD__DEFAULT_USERNAME: admin
      EMQX_DASHBOARD__DEFAULT_PASSWORD: edgeguardian
    ports:
      - "1883:1883"      # MQTT
      - "8883:8883"      # MQTTS
      - "8083:8083"      # MQTT WebSocket
      - "18083:18083"    # EMQX Dashboard
    volumes:
      - emqx_data:/opt/emqx/data
      - ./emqx/acl.conf:/opt/emqx/etc/acl.conf
    healthcheck:
      test: ["CMD", "emqx", "ctl", "status"]
      interval: 10s
      timeout: 5s
      retries: 5
```

**File: `deployments/emqx/acl.conf`** -- ACL rules:
```
%% Device can only publish to its own topics
{allow, {user, "device:${deviceId}"}, publish, ["edgeguardian/device/${deviceId}/telemetry", "edgeguardian/device/${deviceId}/logs"]}.

%% Device can only subscribe to its own command topic
{allow, {user, "device:${deviceId}"}, subscribe, ["edgeguardian/device/${deviceId}/command"]}.

%% Controller has full access
{allow, {user, "edgeguardian-controller"}, all, ["#"]}.

%% Deny all other
{deny, all}.
```

EMQX JWT auth plugin configuration: validate device tokens with the same JWT signing key the controller uses to issue device tokens.

**Controller changes:** `MqttConfig.java` stays mostly the same -- EMQX is MQTT protocol-compatible. The broker URL and credentials may need updating in `application.yaml` if EMQX uses different auth for the controller client.

### 3.2 Agent Log Forwarding

**New directory: `agent/internal/logfwd/`**

`agent/internal/logfwd/forwarder.go`:
```go
package logfwd

type Config struct {
    Enabled    bool     `yaml:"enabled"`
    Sources    []string `yaml:"sources"`    // "journald", "file:/var/log/app.log"
    Transport  string   `yaml:"transport"`  // "mqtt" or "http"
    BufferSize int      `yaml:"buffer_size"`
    BatchSize  int      `yaml:"batch_size"`
    FlushInterval time.Duration `yaml:"flush_interval"`
}

type LogEntry struct {
    Timestamp time.Time         `json:"timestamp"`
    Level     string            `json:"level"`
    Message   string            `json:"message"`
    Source    string            `json:"source"`
    Labels   map[string]string  `json:"labels,omitempty"`
}

type Forwarder struct {
    cfg     Config
    buffer  *ring.Buffer  // ring buffer to avoid OOM
    mqtt    *comms.MQTTClient
    logger  *zap.Logger
}

func (f *Forwarder) Run(ctx context.Context) {
    // 1. Start source readers (journald via sdjournal, file via tail)
    // 2. Feed entries into ring buffer
    // 3. Batch flush loop: drain buffer, publish as batch to MQTT topic
    //    edgeguardian/device/{id}/logs
}
```

`agent/internal/logfwd/journald_linux.go`:
```go
//go:build linux

// Uses sdjournal bindings or shells out to journalctl -f --output=json
```

`agent/internal/logfwd/file_reader.go`:
```go
// Tail-follows a file with inotify (Linux) or polling
```

**Agent config additions:**
```go
type Config struct {
    // ... existing ...
    LogForwarding LogForwardingConfig `yaml:"log_forwarding"`
}

type LogForwardingConfig struct {
    Enabled       bool     `yaml:"enabled"`
    Sources       []string `yaml:"sources"`
    Transport     string   `yaml:"transport"`
    BufferSize    int      `yaml:"buffer_size"`
    BatchSize     int      `yaml:"batch_size"`
    FlushInterval int      `yaml:"flush_interval_seconds"`
}
```

### 3.3 Loki + Grafana Setup

**Modify: `deployments/docker-compose.yml`**:
```yaml
  loki:
    image: grafana/loki:3.3
    container_name: edgeguardian-loki
    command: -config.file=/etc/loki/loki.yaml
    ports:
      - "3100:3100"
    volumes:
      - ./loki/loki.yaml:/etc/loki/loki.yaml
      - loki_data:/loki

  grafana:
    image: grafana/grafana:11.4
    container_name: edgeguardian-grafana
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: edgeguardian
      GF_AUTH_GENERIC_OAUTH_ENABLED: "true"
      GF_AUTH_GENERIC_OAUTH_NAME: "Keycloak"
      GF_AUTH_GENERIC_OAUTH_CLIENT_ID: "grafana"
      GF_AUTH_GENERIC_OAUTH_AUTH_URL: "http://localhost:8080/realms/edgeguardian/protocol/openid-connect/auth"
      GF_AUTH_GENERIC_OAUTH_TOKEN_URL: "http://keycloak:8080/realms/edgeguardian/protocol/openid-connect/token"
    ports:
      - "3001:3000"
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning
      - grafana_data:/var/lib/grafana
    depends_on:
      - loki
```

**File: `deployments/loki/loki.yaml`** -- Minimal local config (single-binary mode).

**File: `deployments/grafana/provisioning/datasources/loki.yaml`**:
```yaml
apiVersion: 1
datasources:
  - name: Loki
    type: loki
    access: proxy
    url: http://loki:3100
    isDefault: true
```

**File: `deployments/grafana/provisioning/dashboards/fleet-overview.json`** -- Pre-built dashboard.

### 3.4 Controller Log Proxy

**Architecture decision:** Agents push logs via MQTT to the controller. The controller's log ingestion service forwards them to Loki's push API. This keeps EMQX as the single ingress point for all agent traffic.

`controller/src/main/java/com/edgeguardian/controller/service/LogService.java`:
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {
    private final WebClient lokiClient;

    @PostConstruct
    void init() {
        // Initialize WebClient for Loki push API
    }

    public void ingestBatch(String deviceId, Long orgId, List<LogEntry> entries) {
        // Transform to Loki push format (streams with labels)
        // POST to http://loki:3100/loki/api/v1/push
    }
}
```

Add new MQTT subscription in `TelemetryListener` or separate `LogIngestionListener`:
- Subscribe to `edgeguardian/device/+/logs`
- Parse log batches and forward to Loki

Dashboard log viewer endpoint:
- `GET /api/v1/organizations/{orgId}/devices/{deviceId}/logs?start=...&end=...&query=...` -- Proxies Loki LogQL query, scopes by device label

### 3.5 OpenTelemetry

**Agent changes:**

Add to `agent/go.mod`:
```
go.opentelemetry.io/otel v1.40.0
go.opentelemetry.io/otel/sdk v1.40.0
go.opentelemetry.io/otel/exporters/otlp/otlptrace v1.40.0
go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp v0.49.0
```

Note: These are already indirect dependencies. Promoting them to direct.

`agent/internal/telemetry/tracing.go`:
```go
package telemetry

func InitTracer(serviceName, otlpEndpoint string) (*sdktrace.TracerProvider, error) {
    exporter, err := otlptrace.New(ctx, otlptracegrpc.NewClient(
        otlptracegrpc.WithEndpoint(otlpEndpoint),
        otlptracegrpc.WithInsecure(),
    ))
    tp := sdktrace.NewTracerProvider(
        sdktrace.WithBatcher(exporter),
        sdktrace.WithResource(resource.NewSchemaless(
            semconv.ServiceNameKey.String(serviceName),
        )),
    )
    otel.SetTracerProvider(tp)
    return tp, nil
}
```

Instrument `http_client.go` -- Wrap `httpClient` with `otelhttp.NewTransport()`.
Add spans around reconciler cycles, MQTT publish, OTA download.

**Controller changes:**

Add to `controller/build.gradle`:
```groovy
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
```

Add to `application.yaml`:
```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
```

---

## WORKSTREAM 4: Phase 5A -- UI Foundation (Week 1-2, parallel with 3A)

### 4.1 Project Setup

The existing `ui/` directory has Next.js 15 + React 19 with only a placeholder page. Needs full setup.

**Modify: `ui/package.json`** -- Add all dependencies:
```json
{
  "dependencies": {
    "next": "^15.2.0",
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "next-auth": "^5.0.0",
    "@tanstack/react-query": "^5.60.0",
    "recharts": "^2.15.0",
    "@monaco-editor/react": "^4.7.0",
    "lucide-react": "^0.460.0",
    "clsx": "^2.1.0",
    "tailwind-merge": "^2.6.0",
    "class-variance-authority": "^0.7.1",
    "date-fns": "^4.1.0",
    "zod": "^3.24.0"
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "@types/react": "^19.0.0",
    "typescript": "^5.7.0",
    "tailwindcss": "^4.0.0",
    "@tailwindcss/postcss": "^4.0.0",
    "postcss": "^8.5.0"
  }
}
```

Run `pnpm dlx shadcn@latest init` to bootstrap shadcn/ui with:
- Style: New York
- Base color: Zinc
- CSS variables: Yes

### 4.2 Auth with next-auth v5

**File: `ui/lib/auth.ts`**:
```typescript
import NextAuth from "next-auth";
import KeycloakProvider from "next-auth/providers/keycloak";

export const { handlers, signIn, signOut, auth } = NextAuth({
  providers: [
    KeycloakProvider({
      clientId: process.env.KEYCLOAK_CLIENT_ID!,
      clientSecret: process.env.KEYCLOAK_CLIENT_SECRET!,
      issuer: process.env.KEYCLOAK_ISSUER!,
    }),
  ],
  callbacks: {
    async jwt({ token, account }) {
      if (account) {
        token.accessToken = account.access_token;
        token.refreshToken = account.refresh_token;
        token.expiresAt = account.expires_at;
      }
      return token;
    },
    async session({ session, token }) {
      session.accessToken = token.accessToken as string;
      return session;
    },
  },
});
```

**File: `ui/app/api/auth/[...nextauth]/route.ts`** -- NextAuth route handler.

**File: `ui/middleware.ts`** -- Protect all routes except `/auth/*`:
```typescript
export { auth as middleware } from "@/lib/auth";

export const config = {
  matcher: ["/((?!auth|api/auth|_next/static|_next/image|favicon.ico).*)"],
};
```

### 4.3 API Client Layer

**File: `ui/lib/api-client.ts`**:
```typescript
import { auth } from "@/lib/auth";

class ApiClient {
  private baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  async fetch<T>(path: string, options?: RequestInit): Promise<T> {
    const session = await auth();
    const res = await fetch(`${this.baseUrl}${path}`, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${session?.accessToken}`,
        ...options?.headers,
      },
    });
    if (!res.ok) throw new ApiError(res.status, await res.text());
    return res.json();
  }
}

export const api = new ApiClient(process.env.NEXT_PUBLIC_API_URL!);
```

**File: `ui/lib/api/devices.ts`** -- Device API functions.
**File: `ui/lib/api/organizations.ts`** -- Organization API functions.
**File: `ui/lib/api/ota.ts`** -- OTA API functions.

### 4.4 Layout Structure

**File: `ui/app/layout.tsx`** -- Root layout with Providers wrapper.

**File: `ui/components/providers.tsx`** -- TanStack Query provider + session provider.

**File: `ui/app/(dashboard)/layout.tsx`** -- Dashboard layout with sidebar:
```
+---------------------------+
| Sidebar   | Top bar       |
| - Logo    | Org switcher  |
| - Devices | User menu     |
| - OTA     +---------------+
| - Settings| Main content  |
| - Audit   |               |
+---------------------------+
```

shadcn/ui components to install:
- `button`, `card`, `input`, `label`, `select`, `table`, `tabs`, `dialog`, `dropdown-menu`, `avatar`, `badge`, `separator`, `sheet`, `skeleton`, `toast`, `tooltip`, `command`, `popover`, `form`, `textarea`, `switch`, `progress`, `alert`

**File: `ui/components/sidebar.tsx`** -- Navigation sidebar with lucide-react icons.
**File: `ui/components/org-switcher.tsx`** -- Organization dropdown.
**File: `ui/components/user-menu.tsx`** -- User avatar + dropdown with sign out.

### 4.5 Auth Pages

**File: `ui/app/auth/login/page.tsx`** -- Login page with "Sign in with Google", "Sign in with GitHub" buttons (these go through Keycloak).

---

## WORKSTREAM 5: Phase 5B -- Core Dashboard Pages (Week 3-4, parallel with 3B)

### 5.1 Dashboard Home

**File: `ui/app/(dashboard)/page.tsx`**:
- Fleet overview cards (total devices, online, offline, degraded counts)
- Recent activity feed from audit log
- Quick stats (active deployments, total artifacts)

### 5.2 Device List Page

**File: `ui/app/(dashboard)/devices/page.tsx`**:
- shadcn DataTable with columns: hostname, device ID, state (badge), arch, OS, agent version, last heartbeat (relative time), labels
- Filter by state, search by hostname/device ID
- Server-side pagination via TanStack Query

**File: `ui/components/devices/device-table.tsx`** -- Table component.
**File: `ui/components/devices/device-filters.tsx`** -- Filter bar.

### 5.3 Device Detail Page

**File: `ui/app/(dashboard)/devices/[id]/page.tsx`**:
- Status cards (CPU, memory, disk, temperature, uptime)
- Metric charts (Recharts line charts, 24h history)
- Labels display (editable for operators/admins)
- Manifest viewer (read-only JSON/YAML display)
- OTA status if deployment in progress

### 5.4 Manifest Editor

**File: `ui/app/(dashboard)/devices/[id]/manifest/page.tsx`**:
- Monaco YAML editor with syntax validation
- "Save & Deploy" button
- Diff view (current vs. proposed)
- Version history sidebar

### 5.5 Shared Components

- `ui/components/metric-card.tsx` -- Reusable stat card with icon, value, label, trend
- `ui/components/state-badge.tsx` -- Online/Offline/Degraded badge
- `ui/components/data-table.tsx` -- Generic DataTable wrapper around shadcn Table + TanStack Table
- `ui/components/loading-skeleton.tsx` -- Skeleton loaders for each page type
- `ui/components/empty-state.tsx` -- Empty state illustrations

---

## WORKSTREAM 6: Phase 5C -- Advanced Pages (Week 5-6, parallel with 3C)

### 6.1 OTA Pages

**File: `ui/app/(dashboard)/ota/page.tsx`**:
- Artifacts list table
- Upload artifact dialog (drag-and-drop file upload, name, version, arch)
- Deployments list table with status badges

**File: `ui/app/(dashboard)/ota/deploy/page.tsx`**:
- Deployment wizard (multi-step form):
  1. Select artifact
  2. Target devices (by labels or individual selection, preview count)
  3. Strategy (all-at-once, rolling, canary)
  4. Review & deploy

**File: `ui/app/(dashboard)/ota/deployments/[id]/page.tsx`**:
- Deployment detail: progress bar, per-device status table, error messages, cancel button

### 6.2 Log Viewer

**File: `ui/app/(dashboard)/devices/[id]/logs/page.tsx`**:
- Real-time log viewer (polling Loki via controller proxy)
- Time range selector
- Log level filter
- Full-text search
- Auto-scroll with pause button

**File: `ui/components/log-viewer.tsx`** -- Core log viewer component.

### 6.3 Settings Pages

**File: `ui/app/(dashboard)/settings/page.tsx`** -- Organization name, danger zone (delete org).
**File: `ui/app/(dashboard)/settings/members/page.tsx`** -- Member list, invite dialog (by email), role dropdown, remove button.
**File: `ui/app/(dashboard)/settings/tokens/page.tsx`** -- Enrollment token management (create dialog with name, max uses, expiry, labels; revoke button).
**File: `ui/app/(dashboard)/settings/api-keys/page.tsx`** -- API key management (create dialog, show key once on creation, revoke button).

### 6.4 Audit Log Page

**File: `ui/app/(dashboard)/audit/page.tsx`**:
- Timeline view of audit events
- Filterable by action type, user, resource
- Pagination
- Details expandable per entry

---

## Complete File Manifest

### Controller -- New Files (30)
```
controller/src/main/java/com/edgeguardian/controller/model/Organization.java
controller/src/main/java/com/edgeguardian/controller/model/User.java
controller/src/main/java/com/edgeguardian/controller/model/OrgRole.java
controller/src/main/java/com/edgeguardian/controller/model/OrganizationMember.java
controller/src/main/java/com/edgeguardian/controller/model/EnrollmentToken.java
controller/src/main/java/com/edgeguardian/controller/model/ApiKey.java
controller/src/main/java/com/edgeguardian/controller/model/AuditLog.java
controller/src/main/java/com/edgeguardian/controller/model/OtaArtifact.java
controller/src/main/java/com/edgeguardian/controller/model/OtaDeployment.java
controller/src/main/java/com/edgeguardian/controller/model/DeploymentDeviceStatus.java
controller/src/main/java/com/edgeguardian/controller/model/DeviceCommand.java
controller/src/main/java/com/edgeguardian/controller/repository/OrganizationRepository.java
controller/src/main/java/com/edgeguardian/controller/repository/UserRepository.java
controller/src/main/java/com/edgeguardian/controller/repository/OrganizationMemberRepository.java
controller/src/main/java/com/edgeguardian/controller/repository/EnrollmentTokenRepository.java
controller/src/main/java/com/edgeguardian/controller/repository/ApiKeyRepository.java
controller/src/main/java/com/edgeguardian/controller/repository/AuditLogRepository.java
controller/src/main/java/com/edgeguardian/controller/repository/OtaArtifactRepository.java
controller/src/main/java/com/edgeguardian/controller/repository/OtaDeploymentRepository.java
controller/src/main/java/com/edgeguardian/controller/repository/DeploymentDeviceStatusRepository.java
controller/src/main/java/com/edgeguardian/controller/service/UserService.java
controller/src/main/java/com/edgeguardian/controller/service/OrganizationService.java
controller/src/main/java/com/edgeguardian/controller/service/EnrollmentService.java
controller/src/main/java/com/edgeguardian/controller/service/ApiKeyService.java
controller/src/main/java/com/edgeguardian/controller/service/AuditService.java
controller/src/main/java/com/edgeguardian/controller/service/OTAService.java
controller/src/main/java/com/edgeguardian/controller/service/LogService.java
controller/src/main/java/com/edgeguardian/controller/service/DeviceHealthScheduler.java
controller/src/main/java/com/edgeguardian/controller/security/SecurityConfig.java
controller/src/main/java/com/edgeguardian/controller/security/ApiKeyAuthenticationFilter.java
controller/src/main/java/com/edgeguardian/controller/security/TenantContext.java
controller/src/main/java/com/edgeguardian/controller/security/TenantInterceptor.java
controller/src/main/java/com/edgeguardian/controller/api/MeController.java
controller/src/main/java/com/edgeguardian/controller/api/OrganizationController.java
controller/src/main/java/com/edgeguardian/controller/api/EnrollmentTokenController.java
controller/src/main/java/com/edgeguardian/controller/api/ApiKeyController.java
controller/src/main/java/com/edgeguardian/controller/api/OTAController.java
controller/src/main/java/com/edgeguardian/controller/api/GlobalExceptionHandler.java
controller/src/main/java/com/edgeguardian/controller/api/ResourceNotFoundException.java
controller/src/main/java/com/edgeguardian/controller/mqtt/LogIngestionListener.java
controller/src/main/resources/db/migration/V2__auth_and_tenancy.sql
controller/src/main/resources/db/migration/V3__ota.sql
```

New DTOs (~20 records, in `controller/src/main/java/com/edgeguardian/controller/dto/`).

### Controller -- Modified Files (10)
```
controller/build.gradle                              -- webflux->web, add security, otel
controller/src/main/resources/application.yaml       -- virtual threads, keycloak JWT, otel
controller/src/main/java/.../EdgeGuardianControllerApplication.java  -- @EnableScheduling
controller/src/main/java/.../model/Device.java       -- @Data, org FK
controller/src/main/java/.../model/DeviceManifestEntity.java -- @Data, org FK
controller/src/main/java/.../model/DeviceStatus.java -- @Data
controller/src/main/java/.../api/DeviceController.java       -- Spring MVC, tenant scoping
controller/src/main/java/.../api/AgentApiController.java     -- enrollment endpoint, auth
controller/src/main/java/.../config/WebConfig.java           -- WebMvcConfigurer
controller/src/main/java/.../repository/DeviceRepository.java       -- tenant queries
controller/src/main/java/.../repository/DeviceManifestRepository.java -- tenant queries
```

### Agent -- New Files (8)
```
agent/internal/ota/updater.go
agent/internal/ota/updater_test.go
agent/internal/commands/dispatcher.go
agent/internal/commands/dispatcher_test.go
agent/internal/logfwd/forwarder.go
agent/internal/logfwd/journald_linux.go
agent/internal/logfwd/file_reader.go
agent/internal/telemetry/tracing.go
```

### Agent -- Modified Files (5)
```
agent/cmd/agent/main.go           -- command dispatcher, enrollment, OTA handler, log fwd
agent/cmd/watchdog/main.go        -- exit code 42, binary swap, rollback
agent/internal/config/config.go   -- OTAConfig, AuthConfig, LogForwardingConfig
agent/internal/comms/http_client.go -- auth token header, Enroll() method
agent/internal/model/manifest.go  -- OTAStatus in heartbeat
agent/internal/storage/store.go   -- auth bucket
```

### Infrastructure -- New Files (6)
```
deployments/keycloak/edgeguardian-realm.json
deployments/emqx/acl.conf
deployments/loki/loki.yaml
deployments/grafana/provisioning/datasources/loki.yaml
deployments/grafana/provisioning/dashboards/fleet-overview.json
```

### Infrastructure -- Modified Files (1)
```
deployments/docker-compose.yml  -- add keycloak, emqx (replace mosquitto), loki, grafana
```

### UI -- New Files (~40-50)
All files under `ui/` as detailed in Workstreams 4-6.

---

## Dependency Sequencing and Risk Areas

**Week 1 critical path:** Keycloak setup and Spring Security must be done first -- everything else depends on auth working. The WebFlux-to-MVC migration is a prerequisite because `spring-boot-starter-web` and `spring-boot-starter-webflux` conflict when both are on the classpath, and the security filter chain differs between them.

**Risk 1:** The existing `AgentApiControllerTest` uses `@WebFluxTest` with `WebTestClient`. After the MVC migration, all tests must switch to `@WebMvcTest` with `MockMvc`. This is a mechanical but broad change.

**Risk 2:** Adding `organization_id` to `devices` and `device_manifests` as nullable means existing tests still pass, but all new queries and tests must consistently provide the org context.

**Risk 3:** The PostgreSQL enum types (`org_role`, `deployment_strategy`, etc.) in the migration need Hibernate's `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` annotation. If using standard JPA, `@Enumerated(EnumType.STRING)` with a `VARCHAR` column is simpler and more portable for tests.

**Risk 4:** EMQX requires its own JWT auth plugin configuration. Testing this locally requires the full docker-compose stack running. Agent MQTT tests with testcontainers may need a generic MQTT container or an EMQX testcontainer.

### Critical Files for Implementation

- `C:\Users\giorg\Desktop\edge-guardian\controller\build.gradle` - Core dependency changes (webflux to web, add security/otel); every workstream depends on this
- `C:\Users\giorg\Desktop\edge-guardian\controller\src\main\resources\db\migration\V1__init.sql` - Existing schema baseline; V2 and V3 migrations must build on this exact schema
- `C:\Users\giorg\Desktop\edge-guardian\controller\src\main\java\com\edgeguardian\controller\service\DeviceRegistry.java` - Central service that must be refactored for tenant scoping; all controllers call through it
- `C:\Users\giorg\Desktop\edge-guardian\agent\cmd\agent\main.go` - Agent entry point where the stub command handler (line 120-126) must be replaced with real OTA/command dispatcher and enrollment flow added
- `C:\Users\giorg\Desktop\edge-guardian\agent\internal\comms\http_client.go` - HTTP client that must gain auth token injection (Authorization header) and the new Enroll() method for the device enrollment flow