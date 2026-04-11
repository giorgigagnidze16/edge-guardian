-- V7__certificates.sql
-- X.509 certificate management: per-org CA, CSR requests, issued certificates.

-- Per-organization Certificate Authority. Private key is AES-256-GCM encrypted.
CREATE TABLE organization_cas (
    id                  BIGSERIAL PRIMARY KEY,
    organization_id     BIGINT NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    ca_cert_pem         TEXT NOT NULL,
    ca_key_encrypted    BYTEA NOT NULL,
    ca_key_iv           BYTEA NOT NULL,
    subject             VARCHAR(255) NOT NULL,
    not_before          TIMESTAMPTZ NOT NULL,
    not_after           TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE certificate_requests (
    id              BIGSERIAL PRIMARY KEY,
    device_id       VARCHAR(255) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    common_name     VARCHAR(255) NOT NULL,
    sans            JSONB NOT NULL DEFAULT '[]',
    csr_pem         TEXT NOT NULL,
    type            VARCHAR(32) NOT NULL DEFAULT 'initial',
    current_serial  VARCHAR(64),
    state           VARCHAR(32) NOT NULL DEFAULT 'pending',
    reject_reason   VARCHAR(512),
    reviewed_by     BIGINT REFERENCES users(id),
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cert_req_state CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'BLOCKED')),
    CONSTRAINT chk_cert_req_type CHECK (type IN ('INITIAL', 'RENEWAL', 'MANIFEST'))
);

CREATE TABLE certificates (
    id              BIGSERIAL PRIMARY KEY,
    request_id      BIGINT REFERENCES certificate_requests(id),
    device_id       VARCHAR(255) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    common_name     VARCHAR(255) NOT NULL,
    serial_number   VARCHAR(64) NOT NULL UNIQUE,
    cert_pem        TEXT NOT NULL,
    not_before      TIMESTAMPTZ NOT NULL,
    not_after       TIMESTAMPTZ NOT NULL,
    replaced_by     BIGINT REFERENCES certificates(id),
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ,
    revoke_reason   VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cert_req_device ON certificate_requests(device_id);
CREATE INDEX idx_cert_req_state ON certificate_requests(state);
CREATE INDEX idx_cert_req_type ON certificate_requests(type);
CREATE INDEX idx_certs_device ON certificates(device_id);
CREATE INDEX idx_certs_not_after ON certificates(not_after) WHERE revoked = FALSE;
