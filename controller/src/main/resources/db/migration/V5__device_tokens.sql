-- V5__device_tokens.sql
-- Device tokens for authenticated agent API access after enrollment.

CREATE TABLE IF NOT EXISTS device_tokens (
    id           BIGSERIAL PRIMARY KEY,
    device_id    VARCHAR(255) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    token_prefix VARCHAR(16) NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    revoked      BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_device_tokens_device UNIQUE (device_id)
);

CREATE INDEX idx_device_tokens_hash ON device_tokens(token_hash);

-- Make organization_id mandatory (all devices must be enrolled to an org).
DELETE FROM device_manifests WHERE organization_id IS NULL;
DELETE FROM devices WHERE organization_id IS NULL;

ALTER TABLE devices ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE device_manifests ALTER COLUMN organization_id SET NOT NULL;
