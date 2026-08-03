-- V7: object storage multipart intents, verified blobs and ingest checkpoints.

ALTER TABLE material_upload_intent
    ADD COLUMN storage_upload_id VARCHAR(1024),
    ADD COLUMN quarantine_object_key VARCHAR(1024),
    ADD COLUMN part_size BIGINT,
    ADD COLUMN part_count INTEGER,
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN upload_state VARCHAR(16) NOT NULL DEFAULT 'INITIATED',
    ADD COLUMN completion_attempt INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_material_upload_intent_state
        CHECK (upload_state IN ('INITIATED', 'UPLOADING', 'COMPLETING', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    ADD CONSTRAINT ck_material_upload_intent_part_size
        CHECK (part_size IS NULL OR part_size BETWEEN 5242880 AND 209715200),
    ADD CONSTRAINT ck_material_upload_intent_part_count
        CHECK (part_count IS NULL OR part_count BETWEEN 1 AND 10000),
    ADD CONSTRAINT ck_material_upload_intent_completion_attempt
        CHECK (completion_attempt >= 0);

CREATE TABLE material_blob (
    id UUID PRIMARY KEY,
    security_partition VARCHAR(16) NOT NULL,
    verified_sha256 CHAR(64) NOT NULL,
    clean_object_key VARCHAR(1024) NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL,
    detected_mime VARCHAR(200) NOT NULL,
    scan_engine_version VARCHAR(100) NOT NULL,
    scan_signature_version VARCHAR(100) NOT NULL,
    parser_name VARCHAR(100),
    parser_version VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_material_blob_content UNIQUE (security_partition, verified_sha256),
    CONSTRAINT ck_material_blob_partition CHECK (security_partition IN ('KNOWLEDGE', 'HOLDOUT')),
    CONSTRAINT ck_material_blob_sha256 CHECK (verified_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_material_blob_size CHECK (size_bytes BETWEEN 1 AND 209715200)
);

ALTER TABLE material
    ADD COLUMN blob_id UUID REFERENCES material_blob(id) ON DELETE RESTRICT;

CREATE INDEX ix_material_blob_partition_sha256 ON material_blob (security_partition, verified_sha256);

CREATE TABLE material_ingest_attempt (
    job_id UUID PRIMARY KEY REFERENCES job(id) ON DELETE RESTRICT,
    material_id UUID NOT NULL REFERENCES material(id) ON DELETE RESTRICT,
    attempt INTEGER NOT NULL,
    stage VARCHAR(40) NOT NULL,
    failure_code VARCHAR(100),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    scan_engine_version VARCHAR(100),
    scan_signature_version VARCHAR(100),
    parser_name VARCHAR(100),
    parser_version VARCHAR(100),
    CONSTRAINT uk_material_ingest_attempt UNIQUE (material_id, attempt),
    CONSTRAINT ck_material_ingest_attempt_stage
        CHECK (stage IN ('STARTED', 'HEAD_VERIFIED', 'HASH_VERIFIED', 'MIME_VERIFIED', 'MALWARE_CLEAN',
                         'ARCHIVE_BUDGET_VERIFIED', 'PARSED', 'CHUNKS_COMMITTED', 'EMBEDDINGS_COMMITTED',
                         'OBJECT_VERIFIED', 'FAILED')),
    CONSTRAINT ck_material_ingest_attempt_completion CHECK ((failure_code IS NULL) OR (completed_at IS NOT NULL))
);

CREATE INDEX ix_material_ingest_attempt_material ON material_ingest_attempt (material_id, attempt);

-- Verified blob content is immutable; successful terminal ingest data must not be rewritten.
CREATE FUNCTION prevent_material_blob_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'material_blob is immutable';
END;
$$;

CREATE TRIGGER trg_material_blob_immutable
BEFORE UPDATE OR DELETE ON material_blob
FOR EACH ROW
EXECUTE FUNCTION prevent_material_blob_mutation();

-- Ingest attempts are append-only for audit; completed rows are not deleted.
CREATE FUNCTION prevent_material_ingest_attempt_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'material_ingest_attempt is append-only';
END;
$$;

CREATE TRIGGER trg_material_ingest_attempt_no_delete
BEFORE DELETE ON material_ingest_attempt
FOR EACH ROW
EXECUTE FUNCTION prevent_material_ingest_attempt_delete();
