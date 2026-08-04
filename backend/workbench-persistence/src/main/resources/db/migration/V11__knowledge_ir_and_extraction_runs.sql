-- V11: durable KnowledgeIR projections and resumable Map/Reduce extraction runs.

CREATE TABLE extraction_run (
    id UUID PRIMARY KEY,
    job_id UUID UNIQUE REFERENCES job(id) ON DELETE RESTRICT,
    document_id UUID NOT NULL,
    sub_scene_id UUID NOT NULL REFERENCES sub_scene(id) ON DELETE RESTRICT,
    round_id UUID NOT NULL REFERENCES extraction_round(id) ON DELETE RESTRICT,
    base_revision_id UUID REFERENCES document_revision(id) ON DELETE RESTRICT,
    base_etag VARCHAR(160),
    model_config_version_id UUID NOT NULL REFERENCES model_config_version(id) ON DELETE RESTRICT,
    skill_version_id UUID NOT NULL REFERENCES skill_version(id) ON DELETE RESTRICT,
    role_config_version_id UUID,
    generation_parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    canonical_input_hash CHAR(64) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_extraction_run_input_hash CHECK (canonical_input_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_extraction_run_schema CHECK (schema_version = 'knowledge-ir/v1'),
    CONSTRAINT ck_extraction_run_stage CHECK (stage IN ('FROZEN', 'MAPPING', 'REDUCING', 'PERSISTING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_extraction_run_base CHECK ((base_revision_id IS NULL) = (base_etag IS NULL))
);

CREATE INDEX ix_extraction_run_document ON extraction_run (document_id, created_at DESC);

CREATE TABLE extraction_run_material (
    run_id UUID NOT NULL REFERENCES extraction_run(id) ON DELETE RESTRICT,
    material_id UUID NOT NULL REFERENCES material(id) ON DELETE RESTRICT,
    verified_sha256 CHAR(64) NOT NULL,
    partition VARCHAR(32) NOT NULL,
    PRIMARY KEY (run_id, material_id),
    CONSTRAINT ck_extraction_run_material_sha CHECK (verified_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_extraction_run_material_partition CHECK (partition IN ('SOURCE', 'LABELED_TRAIN'))
);

CREATE TABLE extraction_run_chunk (
    run_id UUID NOT NULL REFERENCES extraction_run(id) ON DELETE RESTRICT,
    chunk_id UUID NOT NULL REFERENCES material_chunk(id) ON DELETE RESTRICT,
    material_id UUID NOT NULL REFERENCES material(id) ON DELETE RESTRICT,
    source_ref_code VARCHAR(120) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    ordinal INTEGER NOT NULL,
    PRIMARY KEY (run_id, chunk_id),
    UNIQUE (run_id, source_ref_code),
    CONSTRAINT ck_extraction_run_chunk_hash CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_extraction_run_chunk_ordinal CHECK (ordinal >= 0)
);

CREATE TABLE extraction_map_result (
    run_id UUID NOT NULL REFERENCES extraction_run(id) ON DELETE RESTRICT,
    chunk_id UUID NOT NULL REFERENCES material_chunk(id) ON DELETE RESTRICT,
    result JSONB NOT NULL,
    result_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, chunk_id),
    CONSTRAINT fk_extraction_map_run_chunk FOREIGN KEY (run_id, chunk_id)
        REFERENCES extraction_run_chunk(run_id, chunk_id) ON DELETE RESTRICT,
    CONSTRAINT ck_extraction_map_result_hash CHECK (result_hash ~ '^[a-f0-9]{64}$')
);

CREATE TABLE extraction_reduce_result (
    run_id UUID PRIMARY KEY REFERENCES extraction_run(id) ON DELETE RESTRICT,
    knowledge_ir JSONB NOT NULL,
    ir_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_extraction_reduce_hash CHECK (ir_hash ~ '^[a-f0-9]{64}$')
);

CREATE TABLE document_revision_projection (
    revision_id UUID PRIMARY KEY REFERENCES document_revision(id) ON DELETE RESTRICT,
    schema_version VARCHAR(64) NOT NULL,
    knowledge_ir JSONB NOT NULL,
    ir_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_document_projection_schema CHECK (schema_version = 'knowledge-ir/v1'),
    CONSTRAINT ck_document_projection_hash CHECK (ir_hash ~ '^[a-f0-9]{64}$')
);

CREATE TABLE document_revision_source_ref (
    revision_id UUID NOT NULL REFERENCES document_revision(id) ON DELETE RESTRICT,
    source_ref_code VARCHAR(120) NOT NULL,
    material_id UUID NOT NULL REFERENCES material(id) ON DELETE RESTRICT,
    material_sha256 CHAR(64) NOT NULL,
    chunk_id UUID NOT NULL REFERENCES material_chunk(id) ON DELETE RESTRICT,
    locator JSONB NOT NULL,
    excerpt_hash CHAR(64) NOT NULL,
    PRIMARY KEY (revision_id, source_ref_code),
    CONSTRAINT ck_document_source_material_sha CHECK (material_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_document_source_excerpt_hash CHECK (excerpt_hash ~ '^[a-f0-9]{64}$')
);

CREATE INDEX ix_document_source_material ON document_revision_source_ref (material_id, revision_id);

CREATE FUNCTION reject_knowledge_terminal_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_extraction_run_material_immutable BEFORE UPDATE OR DELETE ON extraction_run_material
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_terminal_mutation();
CREATE TRIGGER trg_extraction_run_chunk_immutable BEFORE UPDATE OR DELETE ON extraction_run_chunk
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_terminal_mutation();
CREATE TRIGGER trg_extraction_map_result_immutable BEFORE UPDATE OR DELETE ON extraction_map_result
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_terminal_mutation();
CREATE TRIGGER trg_extraction_reduce_result_immutable BEFORE UPDATE OR DELETE ON extraction_reduce_result
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_terminal_mutation();
CREATE TRIGGER trg_document_projection_immutable BEFORE UPDATE OR DELETE ON document_revision_projection
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_terminal_mutation();
CREATE TRIGGER trg_document_source_ref_immutable BEFORE UPDATE OR DELETE ON document_revision_source_ref
    FOR EACH ROW EXECUTE FUNCTION reject_knowledge_terminal_mutation();
