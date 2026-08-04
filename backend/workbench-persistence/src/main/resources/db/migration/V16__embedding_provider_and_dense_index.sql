-- V16: bind immutable embedding profiles to encrypted model connections and
-- move mutable activation state into its own audited pointer row.

ALTER TABLE embedding_profile_version
    ADD COLUMN model_connection_id UUID REFERENCES model_connection(id) ON DELETE RESTRICT;

ALTER TABLE embedding_profile_version
    DROP CONSTRAINT uk_embedding_profile_version,
    ADD CONSTRAINT uk_embedding_profile_version
        UNIQUE (model_connection_id, model_id, dimension, profile_version);

DROP INDEX uq_embedding_profile_active;

CREATE TABLE embedding_profile_activation (
    singleton_key SMALLINT PRIMARY KEY DEFAULT 1,
    profile_version_id UUID NOT NULL UNIQUE
        REFERENCES embedding_profile_version(id) ON DELETE RESTRICT,
    activated_by UUID NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    activated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_embedding_profile_activation_singleton CHECK (singleton_key = 1)
);

-- Legacy profiles did not identify a credential-bearing model connection and
-- therefore cannot be activated safely. They remain immutable history but are
-- deliberately not copied into the new activation pointer.

CREATE INDEX ix_embedding_profile_connection
    ON embedding_profile_version (model_connection_id, created_at DESC)
    WHERE model_connection_id IS NOT NULL;
