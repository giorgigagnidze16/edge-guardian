-- Certificate Revocation List (CRL) per organization.
-- One row per org; rewritten in-place each time a cert is revoked.
-- The CRL DER blob is served unauthenticated at /api/v1/pki/crl/{orgId}.crl
-- and consumed by TLS verifiers (EMQX, controller mTLS endpoints, agent peers).
CREATE TABLE certificate_revocation_lists (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    crl_number       BIGINT NOT NULL DEFAULT 1,
    crl_der          BYTEA  NOT NULL,
    this_update      TIMESTAMPTZ NOT NULL,
    next_update      TIMESTAMPTZ NOT NULL,
    revoked_count    INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_crl_next_update ON certificate_revocation_lists(next_update);
