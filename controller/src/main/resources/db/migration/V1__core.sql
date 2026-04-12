-- Tenancy, devices, auth, audit.

CREATE TABLE organizations (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id           BIGSERIAL PRIMARY KEY,
    keycloak_id  VARCHAR(255) NOT NULL UNIQUE,
    email        VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    avatar_url   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE organization_members (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_role        VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_member UNIQUE (organization_id, user_id),
    CONSTRAINT chk_org_role  CHECK (org_role IN ('OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'))
);
CREATE INDEX idx_org_members_org_id  ON organization_members(organization_id);
CREATE INDEX idx_org_members_user_id ON organization_members(user_id);

CREATE TABLE devices (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(255) NOT NULL UNIQUE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    hostname        VARCHAR(255),
    architecture    VARCHAR(64),
    os              VARCHAR(64),
    agent_version   VARCHAR(64),
    state           VARCHAR(32) NOT NULL DEFAULT 'ONLINE',
    labels          JSONB NOT NULL DEFAULT '{}',
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_heartbeat  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_devices_state  ON devices(state);
CREATE INDEX idx_devices_labels ON devices USING GIN(labels);
CREATE INDEX idx_devices_org_id ON devices(organization_id);

CREATE TABLE device_manifests (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(255) NOT NULL UNIQUE REFERENCES devices(device_id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    api_version     VARCHAR(64) NOT NULL DEFAULT 'edgeguardian/v1',
    kind            VARCHAR(64) NOT NULL DEFAULT 'DeviceManifest',
    metadata        JSONB NOT NULL DEFAULT '{}',
    spec            JSONB NOT NULL DEFAULT '{}',
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_device_manifests_org_id ON device_manifests(organization_id);

CREATE TABLE enrollment_tokens (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    token           VARCHAR(255) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    expires_at      TIMESTAMPTZ,
    max_uses        INTEGER,
    use_count       INTEGER NOT NULL DEFAULT 0,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_enrollment_tokens_org_id ON enrollment_tokens(organization_id);

CREATE TABLE api_keys (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    key_hash        VARCHAR(255) NOT NULL UNIQUE,
    key_prefix      VARCHAR(16) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    scopes          JSONB NOT NULL DEFAULT '[]',
    expires_at      TIMESTAMPTZ,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    last_used_at    TIMESTAMPTZ,
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_api_keys_org_id ON api_keys(organization_id);

CREATE TABLE device_tokens (
    id           BIGSERIAL PRIMARY KEY,
    device_id    VARCHAR(255) NOT NULL UNIQUE REFERENCES devices(device_id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    token_prefix VARCHAR(16) NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    revoked      BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT REFERENCES organizations(id) ON DELETE SET NULL,
    user_id         BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action          VARCHAR(128) NOT NULL,
    resource_type   VARCHAR(128) NOT NULL,
    resource_id     VARCHAR(255),
    details         JSONB,
    ip_address      VARCHAR(45),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_log_org_id     ON audit_log(organization_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
CREATE INDEX idx_audit_log_action     ON audit_log(action);
