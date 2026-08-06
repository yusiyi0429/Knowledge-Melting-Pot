CREATE TABLE agent_execution_attempt (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES job(id),
    job_attempt INTEGER NOT NULL CHECK (job_attempt > 0),
    role VARCHAR(64) NOT NULL,
    asset_type VARCHAR(64) NOT NULL,
    asset_id UUID NOT NULL REFERENCES asset(id),
    model_config_version_id UUID NOT NULL,
    skill_version_id UUID NOT NULL,
    role_config_version_id UUID,
    effective_config_hash CHAR(64) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    output_hash CHAR(64) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    failure_code VARCHAR(160) NOT NULL DEFAULT '',
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (job_id, job_attempt, asset_type)
);

CREATE UNIQUE INDEX uk_agent_execution_attempt_asset
    ON agent_execution_attempt(asset_id);

CREATE INDEX idx_agent_execution_attempt_job
    ON agent_execution_attempt(job_id, started_at, asset_type);
