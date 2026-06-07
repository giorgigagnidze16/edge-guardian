ALTER TABLE devices ADD COLUMN auto_update BOOLEAN;

ALTER TABLE organizations
    ADD COLUMN current_agent_artifact_id BIGINT REFERENCES ota_artifacts(id) ON DELETE SET NULL;
