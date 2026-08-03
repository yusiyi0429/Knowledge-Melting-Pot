CREATE TABLE model_connection (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    credential_envelope TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    validation_status VARCHAR(64) NOT NULL DEFAULT 'UNTESTED',
    last_validated_at TIMESTAMPTZ,
    next_config_version INTEGER NOT NULL DEFAULT 1,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_model_connection_provider
        CHECK (provider IN ('OPENAI_COMPATIBLE', 'DASHSCOPE')),
    CONSTRAINT ck_model_connection_base_url_https
        CHECK (base_url LIKE 'https://%'),
    CONSTRAINT ck_model_connection_credential_envelope
        CHECK (credential_envelope IS NULL OR credential_envelope LIKE 'kmp1.%'),
    CONSTRAINT ck_model_connection_validation_status
        CHECK (validation_status IN ('UNTESTED', 'CONFIGURATION_VALIDATED')),
    CONSTRAINT ck_model_connection_validation_time
        CHECK (validation_status <> 'CONFIGURATION_VALIDATED' OR last_validated_at IS NOT NULL),
    CONSTRAINT ck_model_connection_next_config_version CHECK (next_config_version > 0)
);

CREATE UNIQUE INDEX uk_model_connection_name_active
    ON model_connection (LOWER(name)) WHERE deleted_at IS NULL;

CREATE INDEX ix_model_connection_active
    ON model_connection (enabled, updated_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE model_config_version (
    id UUID PRIMARY KEY,
    model_connection_id UUID NOT NULL REFERENCES model_connection(id) ON DELETE RESTRICT,
    version INTEGER NOT NULL,
    model_id VARCHAR(300) NOT NULL,
    temperature NUMERIC(4, 2) NOT NULL,
    max_output_tokens INTEGER NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (model_connection_id, version),
    CONSTRAINT ck_model_config_version_positive CHECK (version > 0),
    CONSTRAINT ck_model_config_temperature CHECK (temperature >= 0 AND temperature <= 2),
    CONSTRAINT ck_model_config_max_output_tokens CHECK (max_output_tokens BETWEEN 1 AND 1000000)
);

CREATE INDEX ix_model_config_version_connection
    ON model_config_version (model_connection_id, version DESC);

CREATE FUNCTION reject_model_config_version_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'model_config_version rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER model_config_version_immutable
    BEFORE UPDATE OR DELETE ON model_config_version
    FOR EACH ROW EXECUTE FUNCTION reject_model_config_version_mutation();
