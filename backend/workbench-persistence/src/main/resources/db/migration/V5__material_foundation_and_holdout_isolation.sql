ALTER TABLE material ADD COLUMN file_format VARCHAR(16);

UPDATE material
SET file_format = CASE media_type
    WHEN 'application/pdf' THEN 'PDF'
    WHEN 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' THEN 'DOCX'
    WHEN 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' THEN 'XLSX'
    WHEN 'text/plain' THEN 'TXT'
    ELSE NULL
END,
sha256 = LOWER(sha256);

ALTER TABLE material ALTER COLUMN file_format SET NOT NULL;

CREATE TABLE round_material (
    id UUID PRIMARY KEY,
    material_id UUID NOT NULL REFERENCES material(id) ON DELETE RESTRICT,
    round_id UUID NOT NULL REFERENCES extraction_round(id) ON DELETE CASCADE,
    sub_scene_id UUID NOT NULL REFERENCES sub_scene(id) ON DELETE CASCADE,
    partition VARCHAR(32) NOT NULL,
    share_scope VARCHAR(32) NOT NULL,
    regulatory_source BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_round_material_binding UNIQUE (material_id, round_id, sub_scene_id),
    CONSTRAINT ck_round_material_partition
        CHECK (partition IN ('SOURCE', 'LABELED_TRAIN', 'LABELED_HOLDOUT')),
    CONSTRAINT ck_round_material_share_scope CHECK (share_scope IN ('ROUND', 'SUBSCENE', 'SCENE')),
    CONSTRAINT ck_round_material_holdout_regulatory
        CHECK (partition <> 'LABELED_HOLDOUT' OR regulatory_source = FALSE)
);

INSERT INTO round_material (
    id, material_id, round_id, sub_scene_id, partition, share_scope, regulatory_source, active, created_at)
SELECT m.id, m.id, m.round_id, r.sub_scene_id, 'SOURCE', 'ROUND', FALSE, m.active, m.created_at
FROM material m
JOIN extraction_round r ON r.id = m.round_id;

DROP INDEX ix_material_round_active;

ALTER TABLE material
    DROP CONSTRAINT material_round_id_fkey,
    DROP CONSTRAINT ck_material_size,
    DROP CONSTRAINT ck_material_status,
    DROP COLUMN round_id,
    DROP COLUMN active,
    ADD CONSTRAINT ck_material_size CHECK (size_bytes BETWEEN 1 AND 209715200),
    ADD CONSTRAINT ck_material_format CHECK (file_format IN ('PDF', 'DOCX', 'XLSX', 'TXT')),
    ADD CONSTRAINT ck_material_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_material_status
        CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'SCANNING', 'READY', 'FAILED', 'INACTIVE')),
    ADD CONSTRAINT uk_material_sha256 UNIQUE (sha256),
    ADD CONSTRAINT uk_material_object_key UNIQUE (object_key);

CREATE INDEX ix_round_material_knowledge
    ON round_material (round_id, sub_scene_id, partition)
    WHERE active = TRUE AND partition IN ('SOURCE', 'LABELED_TRAIN');

CREATE INDEX ix_round_material_evaluation
    ON round_material (round_id, sub_scene_id)
    WHERE active = TRUE AND partition = 'LABELED_HOLDOUT';

CREATE TABLE material_upload_intent (
    id UUID PRIMARY KEY,
    material_id UUID NOT NULL UNIQUE REFERENCES material(id) ON DELETE RESTRICT,
    created_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    validation_job_id UUID UNIQUE REFERENCES job(id) ON DELETE RESTRICT,
    client_etag VARCHAR(200) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_material_upload_completion
        CHECK ((validation_job_id IS NULL AND completed_at IS NULL AND client_etag = '')
            OR (validation_job_id IS NOT NULL AND completed_at IS NOT NULL AND client_etag <> ''))
);

CREATE FUNCTION prevent_material_metadata_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(OLD.file_name, OLD.file_format, OLD.media_type, OLD.object_key, OLD.sha256,
           OLD.size_bytes, OLD.created_at)
       IS DISTINCT FROM
       ROW(NEW.file_name, NEW.file_format, NEW.media_type, NEW.object_key, NEW.sha256,
           NEW.size_bytes, NEW.created_at) THEN
        RAISE EXCEPTION 'material file metadata is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_material_metadata_immutable
BEFORE UPDATE ON material
FOR EACH ROW
EXECUTE FUNCTION prevent_material_metadata_update();
