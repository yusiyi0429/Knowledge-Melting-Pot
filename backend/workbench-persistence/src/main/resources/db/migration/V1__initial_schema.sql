CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE UNIQUE INDEX uk_app_user_username_lower ON app_user (LOWER(username));

CREATE TABLE app_user_role (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_app_user_role CHECK (role IN ('OPERATOR', 'PUBLISHER', 'ADMIN'))
);

CREATE TABLE scene (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_scene_updated_at ON scene (updated_at DESC);

CREATE TABLE sub_scene (
    id UUID PRIMARY KEY,
    scene_id UUID NOT NULL REFERENCES scene(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (scene_id, name)
);

CREATE INDEX ix_sub_scene_scene ON sub_scene (scene_id, created_at);

CREATE TABLE extraction_round (
    id UUID PRIMARY KEY,
    sub_scene_id UUID NOT NULL REFERENCES sub_scene(id) ON DELETE CASCADE,
    round_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (sub_scene_id, round_number),
    CONSTRAINT ck_extraction_round_number CHECK (round_number > 0),
    CONSTRAINT ck_extraction_round_status CHECK (status IN ('DRAFT', 'PROCESSING', 'READY', 'FAILED', 'SUPERSEDED'))
);

CREATE TABLE material (
    id UUID PRIMARY KEY,
    round_id UUID NOT NULL REFERENCES extraction_round(id) ON DELETE CASCADE,
    file_name VARCHAR(500) NOT NULL,
    media_type VARCHAR(200) NOT NULL,
    object_key VARCHAR(1000) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_material_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_material_status CHECK (status IN ('UPLOADED', 'SCANNING', 'READY', 'FAILED', 'INACTIVE'))
);

CREATE INDEX ix_material_round_active ON material (round_id, active);

CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY,
    sub_scene_id UUID NOT NULL REFERENCES sub_scene(id) ON DELETE CASCADE,
    current_revision BIGINT NOT NULL DEFAULT 0,
    current_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (sub_scene_id)
);

CREATE TABLE document_revision (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    base_revision_id UUID REFERENCES document_revision(id),
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_id, revision)
);

ALTER TABLE knowledge_document
    ADD CONSTRAINT fk_knowledge_document_current_revision
    FOREIGN KEY (current_revision_id) REFERENCES document_revision(id);

CREATE INDEX ix_document_revision_document ON document_revision (document_id, revision DESC);

CREATE TABLE asset (
    id UUID PRIMARY KEY,
    sub_scene_id UUID NOT NULL REFERENCES sub_scene(id) ON DELETE CASCADE,
    asset_type VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    document_revision_id UUID REFERENCES document_revision(id),
    object_key VARCHAR(1000) NOT NULL DEFAULT '',
    checksum VARCHAR(128) NOT NULL DEFAULT '',
    failure_reason TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (sub_scene_id, asset_type, version),
    CONSTRAINT ck_asset_version CHECK (version > 0),
    CONSTRAINT ck_asset_type CHECK (asset_type IN ('RULE_CATALOG', 'DECISION_FLOW', 'SKILL_PACKAGE', 'QA_PAIRS', 'EVALUATION_SET')),
    CONSTRAINT ck_asset_status CHECK (status IN ('PENDING', 'GENERATING', 'READY', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT ck_ready_asset_content CHECK (status <> 'READY' OR (object_key <> '' AND checksum <> ''))
);

CREATE INDEX ix_asset_latest ON asset (sub_scene_id, asset_type, version DESC);

CREATE TABLE job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_reference VARCHAR(1000) NOT NULL DEFAULT '',
    error_code VARCHAR(100) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    requested_by UUID NOT NULL REFERENCES app_user(id),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    locked_by VARCHAR(200),
    lease_until TIMESTAMPTZ,
    attempt INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_job_type CHECK (job_type IN ('INGEST', 'EXTRACT', 'REEXTRACT', 'ALIGN', 'GENERATE_ASSET', 'GENERATE_ALL', 'SCENE_EXPLORE', 'EVALUATE')),
    CONSTRAINT ck_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_job_progress CHECK (progress BETWEEN 0 AND 100)
);

CREATE INDEX ix_job_claim ON job (status, lease_until, created_at);
CREATE INDEX ix_job_requested_by ON job (requested_by, created_at DESC);

CREATE TABLE job_event (
    sequence BIGSERIAL PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_job_event_replay ON job_event (job_id, sequence);

CREATE TABLE idempotency_record (
    scope VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX ix_idempotency_expiry ON idempotency_record (expires_at);

CREATE TABLE release_snapshot (
    id UUID PRIMARY KEY,
    scene_id UUID NOT NULL REFERENCES scene(id),
    version VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    partial BOOLEAN NOT NULL,
    manifest JSONB NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    UNIQUE (scene_id, version),
    CONSTRAINT ck_release_status CHECK (status IN ('DRAFT', 'VALIDATING', 'PUBLISHED', 'FAILED'))
);

CREATE TABLE release_item (
    release_id UUID NOT NULL REFERENCES release_snapshot(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES asset(id),
    sub_scene_id UUID NOT NULL REFERENCES sub_scene(id),
    asset_type VARCHAR(64) NOT NULL,
    asset_version INTEGER NOT NULL,
    document_revision_id UUID REFERENCES document_revision(id),
    object_key VARCHAR(1000) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    PRIMARY KEY (release_id, sub_scene_id, asset_type)
);

CREATE INDEX ix_release_scene_published ON release_snapshot (scene_id, published_at DESC);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id UUID NOT NULL,
    details JSONB NOT NULL,
    trace_id VARCHAR(100) NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_audit_target ON audit_log (target_type, target_id, occurred_at DESC);
CREATE INDEX ix_audit_actor ON audit_log (actor_id, occurred_at DESC);
