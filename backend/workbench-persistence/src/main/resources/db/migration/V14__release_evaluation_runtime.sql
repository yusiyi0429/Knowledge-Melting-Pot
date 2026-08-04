-- V14: release-bound Holdout evaluation runs, immutable cases and normalized results.

CREATE TABLE evaluation_run (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES release_snapshot(id) ON DELETE RESTRICT,
    sub_scene_id UUID NOT NULL REFERENCES sub_scene(id) ON DELETE RESTRICT,
    round_id UUID NOT NULL REFERENCES extraction_round(id) ON DELETE RESTRICT,
    document_revision_id UUID NOT NULL REFERENCES document_revision(id) ON DELETE RESTRICT,
    evaluation_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    skill_asset_id UUID NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    model_config_version_id UUID NOT NULL REFERENCES model_config_version(id) ON DELETE RESTRICT,
    skill_version_id UUID NOT NULL REFERENCES skill_version(id) ON DELETE RESTRICT,
    job_id UUID NOT NULL UNIQUE REFERENCES job(id) ON DELETE RESTRICT,
    case_set_hash CHAR(64),
    status VARCHAR(24) NOT NULL,
    total_cases INTEGER NOT NULL DEFAULT 0,
    passed_cases INTEGER NOT NULL DEFAULT 0,
    failed_cases INTEGER NOT NULL DEFAULT 0,
    error_cases INTEGER NOT NULL DEFAULT 0,
    accuracy NUMERIC(7,6),
    failure_code VARCHAR(100) NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_evaluation_run_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_evaluation_run_counts CHECK (
        total_cases >= 0 AND passed_cases >= 0 AND failed_cases >= 0 AND error_cases >= 0
        AND passed_cases + failed_cases + error_cases <= total_cases),
    CONSTRAINT ck_evaluation_run_accuracy CHECK (accuracy IS NULL OR (accuracy >= 0 AND accuracy <= 1)),
    CONSTRAINT ck_evaluation_run_case_hash CHECK (
        case_set_hash IS NULL OR case_set_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_evaluation_run_completion CHECK (
        (status = 'SUCCEEDED' AND total_cases > 0 AND accuracy IS NOT NULL AND completed_at IS NOT NULL)
        OR status <> 'SUCCEEDED')
);

CREATE INDEX ix_evaluation_run_release_sub_scene
    ON evaluation_run (release_id, sub_scene_id, created_at DESC);

CREATE TABLE evaluation_case (
    id UUID PRIMARY KEY,
    evaluation_run_id UUID NOT NULL REFERENCES evaluation_run(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL,
    case_key VARCHAR(120) NOT NULL,
    input_text TEXT NOT NULL,
    expected_text VARCHAR(500) NOT NULL,
    material_id UUID NOT NULL REFERENCES material(id) ON DELETE RESTRICT,
    chunk_id UUID NOT NULL REFERENCES material_chunk(id) ON DELETE RESTRICT,
    source_ref_code VARCHAR(120) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (evaluation_run_id, ordinal),
    UNIQUE (evaluation_run_id, case_key),
    UNIQUE (evaluation_run_id, id),
    CONSTRAINT ck_evaluation_case_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_evaluation_case_input CHECK (char_length(input_text) BETWEEN 1 AND 20000),
    CONSTRAINT ck_evaluation_case_expected CHECK (char_length(expected_text) BETWEEN 1 AND 500),
    CONSTRAINT ck_evaluation_case_content_hash CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_evaluation_case_tags CHECK (jsonb_typeof(tags) = 'array')
);

CREATE TABLE evaluation_case_result (
    case_id UUID PRIMARY KEY,
    evaluation_run_id UUID NOT NULL,
    prediction VARCHAR(500) NOT NULL DEFAULT '',
    outcome VARCHAR(16) NOT NULL,
    error_code VARCHAR(100) NOT NULL DEFAULT '',
    latency_millis BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_evaluation_result_case
        FOREIGN KEY (evaluation_run_id, case_id)
        REFERENCES evaluation_case(evaluation_run_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_evaluation_result_outcome CHECK (outcome IN ('PASSED', 'FAILED', 'ERROR')),
    CONSTRAINT ck_evaluation_result_latency CHECK (latency_millis BETWEEN 0 AND 3600000),
    CONSTRAINT ck_evaluation_result_error CHECK (outcome <> 'ERROR' OR error_code <> '')
);

CREATE FUNCTION prevent_evaluation_input_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(OLD.release_id, OLD.sub_scene_id, OLD.round_id, OLD.document_revision_id,
           OLD.evaluation_asset_id, OLD.skill_asset_id, OLD.model_config_version_id,
           OLD.skill_version_id, OLD.job_id, OLD.created_by, OLD.created_at)
       IS DISTINCT FROM
       ROW(NEW.release_id, NEW.sub_scene_id, NEW.round_id, NEW.document_revision_id,
           NEW.evaluation_asset_id, NEW.skill_asset_id, NEW.model_config_version_id,
           NEW.skill_version_id, NEW.job_id, NEW.created_by, NEW.created_at) THEN
        RAISE EXCEPTION 'evaluation run inputs are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_evaluation_run_input_immutable
BEFORE UPDATE ON evaluation_run
FOR EACH ROW EXECUTE FUNCTION prevent_evaluation_input_mutation();

CREATE FUNCTION prevent_evaluation_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'evaluation cases and results are immutable';
END;
$$;

CREATE TRIGGER trg_evaluation_case_immutable
BEFORE UPDATE OR DELETE ON evaluation_case
FOR EACH ROW EXECUTE FUNCTION prevent_evaluation_evidence_mutation();

CREATE TRIGGER trg_evaluation_result_immutable
BEFORE UPDATE OR DELETE ON evaluation_case_result
FOR EACH ROW EXECUTE FUNCTION prevent_evaluation_evidence_mutation();
