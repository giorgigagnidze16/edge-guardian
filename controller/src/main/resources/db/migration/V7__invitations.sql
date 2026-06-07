-- Pending organization invitations. A row exists only until the invited email
-- registers (first login) and is converted into an organization_members row,
-- or an admin revokes it. Invites for already-registered users are added
-- directly and never land here. OWNER is never invitable.
CREATE TABLE organization_invitations (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email           VARCHAR(255) NOT NULL,
    org_role        VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    invited_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_invite UNIQUE (organization_id, email),
    CONSTRAINT chk_invite_role CHECK (org_role IN ('ADMIN', 'OPERATOR', 'VIEWER'))
);

-- Resolved by email on first login.
CREATE INDEX idx_org_invitations_email ON organization_invitations(email);
CREATE INDEX idx_org_invitations_org_id ON organization_invitations(organization_id);
