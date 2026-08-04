-- V9: minimal Skill library (resource-only metadata + immutable versions).
-- Skills are TEMPLATE (global) or INSTANCE (bound to one scene). A fork lineage
-- is recorded on the instance; every skill and every version is immutable.
-- Manifest JSON is validated by the application layer; nothing here executes it.

CREATE TABLE skill (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    scene_id UUID REFERENCES scene(id) ON DELETE RESTRICT,
    source_skill_id UUID REFERENCES skill(id) ON DELETE RESTRICT,
    source_skill_version_id UUID,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_skill_kind CHECK (kind IN ('TEMPLATE', 'INSTANCE')),
    CONSTRAINT ck_skill_template_global CHECK (kind <> 'TEMPLATE' OR scene_id IS NULL),
    CONSTRAINT ck_skill_instance_scene CHECK (kind <> 'INSTANCE' OR scene_id IS NOT NULL),
    CONSTRAINT ck_skill_instance_lineage CHECK (
        (kind = 'INSTANCE' AND source_skill_id IS NOT NULL AND source_skill_version_id IS NOT NULL)
        OR (kind = 'TEMPLATE' AND source_skill_id IS NULL AND source_skill_version_id IS NULL)
    )
);

CREATE TABLE skill_version (
    id UUID PRIMARY KEY,
    skill_id UUID NOT NULL REFERENCES skill(id) ON DELETE RESTRICT,
    version INTEGER NOT NULL,
    manifest_json TEXT NOT NULL,
    package_hash VARCHAR(128) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (skill_id, version),
    CONSTRAINT ck_skill_version_positive CHECK (version > 0),
    CONSTRAINT ck_skill_version_hash CHECK (package_hash ~ '^[0-9a-f]{64}$')
);

-- The version the instance was forked from (lineage back to the source template).
-- The column exists from CREATE TABLE; the FK is added once skill_version exists.
ALTER TABLE skill
    ADD CONSTRAINT fk_skill_source_version
    FOREIGN KEY (source_skill_version_id) REFERENCES skill_version(id) ON DELETE RESTRICT;

CREATE INDEX ix_skill_kind_scene ON skill (kind, scene_id);
CREATE INDEX ix_skill_version_skill ON skill_version (skill_id, version DESC);

CREATE FUNCTION reject_skill_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'skill rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_skill_immutable
    BEFORE UPDATE OR DELETE ON skill
    FOR EACH ROW EXECUTE FUNCTION reject_skill_mutation();

CREATE FUNCTION reject_skill_version_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'skill_version rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_skill_version_immutable
    BEFORE UPDATE OR DELETE ON skill_version
    FOR EACH ROW EXECUTE FUNCTION reject_skill_version_mutation();
