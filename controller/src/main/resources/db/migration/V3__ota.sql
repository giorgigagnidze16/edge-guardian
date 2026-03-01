-- V3__ota.sql
-- EdgeGuardian Phase 3: OTA artifact and deployment management.

-- OTA artifacts (uploaded binaries, configs, etc.)
CREATE TABLE IF NOT EXISTS ota_artifacts (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    version         VARCHAR(64) NOT NULL,
    architecture    VARCHAR(64) NOT NULL,
    size            BIGINT NOT NULL,
    sha256          VARCHAR(64) NOT NULL,
    ed25519_sig     TEXT,
    s3_key          VARCHAR(512),
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_artifact_version UNIQUE (organization_id, name, version, architecture)
);

-- OTA deployments (rollout of an artifact to devices)
CREATE TABLE IF NOT EXISTS ota_deployments (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    artifact_id     BIGINT NOT NULL REFERENCES ota_artifacts(id) ON DELETE CASCADE,
    strategy        VARCHAR(32) NOT NULL DEFAULT 'rolling',
    state           VARCHAR(32) NOT NULL DEFAULT 'pending',
    label_selector  JSONB NOT NULL DEFAULT '{}',
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_deployment_strategy CHECK (strategy IN ('rolling', 'canary', 'immediate')),
    CONSTRAINT chk_deployment_state CHECK (state IN ('pending', 'in_progress', 'completed', 'failed', 'cancelled'))
);

-- Per-device OTA deployment status
CREATE TABLE IF NOT EXISTS deployment_device_status (
    id              BIGSERIAL PRIMARY KEY,
    deployment_id   BIGINT NOT NULL REFERENCES ota_deployments(id) ON DELETE CASCADE,
    device_id       VARCHAR(255) NOT NULL,
    state           VARCHAR(32) NOT NULL DEFAULT 'pending',
    progress        INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    started_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_deployment_device UNIQUE (deployment_id, device_id),
    CONSTRAINT chk_device_ota_state CHECK (state IN ('pending', 'downloading', 'verifying', 'applying', 'completed', 'failed', 'rolled_back'))
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_ota_artifacts_org_id ON ota_artifacts(organization_id);
CREATE INDEX IF NOT EXISTS idx_ota_deployments_org_id ON ota_deployments(organization_id);
CREATE INDEX IF NOT EXISTS idx_ota_deployments_artifact ON ota_deployments(artifact_id);
CREATE INDEX IF NOT EXISTS idx_ota_deployments_state ON ota_deployments(state);
CREATE INDEX IF NOT EXISTS idx_deployment_device_status_deployment ON deployment_device_status(deployment_id);
CREATE INDEX IF NOT EXISTS idx_deployment_device_status_device ON deployment_device_status(device_id);
