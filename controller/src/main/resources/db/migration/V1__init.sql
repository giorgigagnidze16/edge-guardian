-- V1__init.sql
-- EdgeGuardian Phase 2: Initial schema for device management.

CREATE TABLE IF NOT EXISTS devices (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(255) NOT NULL UNIQUE,
    hostname        VARCHAR(255),
    architecture    VARCHAR(64),
    os              VARCHAR(64),
    agent_version   VARCHAR(64),
    state           VARCHAR(32) NOT NULL DEFAULT 'ONLINE',
    labels          JSONB NOT NULL DEFAULT '{}',
    registered_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_heartbeat  TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS device_manifests (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(255) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    api_version     VARCHAR(64) NOT NULL DEFAULT 'edgeguardian/v1',
    kind            VARCHAR(64) NOT NULL DEFAULT 'DeviceManifest',
    metadata        JSONB NOT NULL DEFAULT '{}',
    spec            JSONB NOT NULL DEFAULT '{}',
    version         BIGINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_device_manifest UNIQUE (device_id)
);

-- Indexes for common queries.
CREATE INDEX IF NOT EXISTS idx_devices_state ON devices(state);
CREATE INDEX IF NOT EXISTS idx_devices_labels ON devices USING GIN(labels);
CREATE INDEX IF NOT EXISTS idx_device_manifests_device_id ON device_manifests(device_id);
