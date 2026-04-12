-- Seed a default organization so PkiBootstrapRunner materializes a CA on first start,
-- and EMQX's init container can fetch a non-empty ca-bundle without waiting for a
-- human to log in. Users created via Keycloak still get auto-provisioned into their
-- own "personal" orgs; this row is just the platform-wide default.
INSERT INTO organizations (name, slug, description)
VALUES ('EdgeGuardian Default', 'default', 'Auto-seeded on first install')
ON CONFLICT (slug) DO NOTHING;
