-- V18: remove scenes from the active workbench without destroying their lineage.

ALTER TABLE scene
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN archived_by UUID REFERENCES app_user(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_scene_archive_actor CHECK (
        (archived_at IS NULL AND archived_by IS NULL)
        OR (archived_at IS NOT NULL AND archived_by IS NOT NULL)
    );

CREATE INDEX ix_scene_active_updated_at
    ON scene (updated_at DESC, id)
    WHERE archived_at IS NULL;
