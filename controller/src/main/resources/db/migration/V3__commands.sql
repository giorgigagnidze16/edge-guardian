CREATE TABLE device_commands (
    id              BIGSERIAL PRIMARY KEY,
    command_id      VARCHAR(64) NOT NULL UNIQUE,
    device_id       VARCHAR(255) NOT NULL,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    type            VARCHAR(64) NOT NULL,
    params          JSONB NOT NULL DEFAULT '{}',
    script          JSONB,
    hooks           JSONB,
    timeout_seconds INTEGER NOT NULL DEFAULT 0,
    state           VARCHAR(32) NOT NULL DEFAULT 'pending',
    created_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT chk_command_state CHECK (state IN ('pending', 'sent', 'running', 'completed', 'failed', 'timeout'))
);
CREATE INDEX idx_device_commands_device ON device_commands(device_id);
CREATE INDEX idx_device_commands_org    ON device_commands(organization_id);
CREATE INDEX idx_device_commands_state  ON device_commands(state);

CREATE TABLE command_executions (
    id            BIGSERIAL PRIMARY KEY,
    command_id    VARCHAR(64) NOT NULL,
    device_id     VARCHAR(255) NOT NULL,
    phase         VARCHAR(32) NOT NULL,
    status        VARCHAR(32) NOT NULL,
    exit_code     INTEGER NOT NULL DEFAULT 0,
    stdout        TEXT,
    stderr        TEXT,
    error_message TEXT,
    duration_ms   BIGINT NOT NULL DEFAULT 0,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_exec_phase  CHECK (phase IN ('pre_hook', 'main', 'post_hook')),
    CONSTRAINT chk_exec_status CHECK (status IN ('success', 'failed', 'running', 'timeout'))
);
CREATE INDEX idx_command_executions_command ON command_executions(command_id);
CREATE INDEX idx_command_executions_device  ON command_executions(device_id);
