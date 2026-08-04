-- V13: staged scene exploration, immutable candidates and durable user notifications.

CREATE TABLE exploration_session (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL,
    explore_job_id UUID UNIQUE REFERENCES job(id) ON DELETE RESTRICT,
    model_config_version_id UUID REFERENCES model_config_version(id) ON DELETE RESTRICT,
    skill_version_id UUID REFERENCES skill_version(id) ON DELETE RESTRICT,
    role_config_version_id UUID,
    effective_config_hash CHAR(64),
    version INTEGER NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_exploration_status CHECK (status IN (
        'DRAFT', 'ANALYZING', 'READY', 'ACCEPTED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_exploration_version CHECK (version >= 0),
    CONSTRAINT ck_exploration_config_hash CHECK (
        effective_config_hash IS NULL OR effective_config_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_exploration_frozen_config CHECK (
        status = 'DRAFT' OR (explore_job_id IS NOT NULL AND model_config_version_id IS NOT NULL
            AND skill_version_id IS NOT NULL AND effective_config_hash IS NOT NULL))
);

CREATE INDEX ix_exploration_owner_updated ON exploration_session (created_by, updated_at DESC);

CREATE TABLE exploration_material (
    session_id UUID NOT NULL REFERENCES exploration_session(id) ON DELETE RESTRICT,
    material_id UUID NOT NULL UNIQUE REFERENCES material(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, material_id),
    UNIQUE (session_id, ordinal),
    CONSTRAINT ck_exploration_material_ordinal CHECK (ordinal >= 0)
);

CREATE TABLE exploration_candidate (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES exploration_session(id) ON DELETE RESTRICT,
    rank INTEGER NOT NULL,
    scene_name VARCHAR(200) NOT NULL,
    scene_description TEXT NOT NULL DEFAULT '',
    sub_scene_name VARCHAR(200) NOT NULL,
    sub_scene_description TEXT NOT NULL DEFAULT '',
    rationale TEXT NOT NULL,
    value_level VARCHAR(16) NOT NULL,
    estimated_rule_count INTEGER NOT NULL DEFAULT 0,
    estimated_flow_count INTEGER NOT NULL DEFAULT 0,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, rank),
    CONSTRAINT ck_exploration_candidate_rank CHECK (rank BETWEEN 1 AND 20),
    CONSTRAINT ck_exploration_candidate_value CHECK (value_level IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_exploration_candidate_estimates CHECK (
        estimated_rule_count >= 0 AND estimated_flow_count >= 0)
);

CREATE TABLE exploration_candidate_material (
    candidate_id UUID NOT NULL REFERENCES exploration_candidate(id) ON DELETE RESTRICT,
    material_id UUID NOT NULL REFERENCES material(id) ON DELETE RESTRICT,
    PRIMARY KEY (candidate_id, material_id)
);

CREATE TABLE exploration_acceptance (
    session_id UUID PRIMARY KEY REFERENCES exploration_session(id) ON DELETE RESTRICT,
    candidate_id UUID NOT NULL UNIQUE REFERENCES exploration_candidate(id) ON DELETE RESTRICT,
    scene_id UUID NOT NULL REFERENCES scene(id) ON DELETE RESTRICT,
    sub_scene_id UUID NOT NULL REFERENCES sub_scene(id) ON DELETE RESTRICT,
    round_id UUID NOT NULL REFERENCES extraction_round(id) ON DELETE RESTRICT,
    accepted_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    accepted_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION prevent_exploration_candidate_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'exploration candidates are immutable';
END;
$$;

CREATE TRIGGER trg_exploration_candidate_immutable
BEFORE UPDATE OR DELETE ON exploration_candidate
FOR EACH ROW EXECUTE FUNCTION prevent_exploration_candidate_mutation();

CREATE TRIGGER trg_exploration_candidate_material_immutable
BEFORE UPDATE OR DELETE ON exploration_candidate_material
FOR EACH ROW EXECUTE FUNCTION prevent_exploration_candidate_mutation();

CREATE TABLE user_notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    notification_type VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(500) NOT NULL DEFAULT '',
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    UNIQUE (user_id, notification_type, resource_type, resource_id)
);

CREATE INDEX ix_user_notification_inbox
    ON user_notification (user_id, read_at, created_at DESC);
