-- V12: immutable seven-role Agent configuration, hierarchical mounts and import previews.

CREATE TABLE agent_role_template_version (
    id UUID PRIMARY KEY,
    role VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    default_options JSONB NOT NULL DEFAULT '{}'::jsonb,
    config_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (role, version),
    CONSTRAINT ck_agent_role_template_role CHECK (role IN (
        'SCENE_EXPLORER', 'KNOWLEDGE_EXTRACTOR', 'ALIGNMENT_REVIEWER',
        'RULE_CATALOG_GENERATOR', 'DECISION_FLOW_GENERATOR', 'SKILL_PACKAGER', 'QA_EVALUATOR')),
    CONSTRAINT ck_agent_role_template_version CHECK (version > 0),
    CONSTRAINT ck_agent_role_template_options CHECK (jsonb_typeof(default_options) = 'object'),
    CONSTRAINT ck_agent_role_template_hash CHECK (config_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE agent_mount_version (
    id UUID PRIMARY KEY,
    role VARCHAR(64) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    scope_key VARCHAR(64) NOT NULL,
    scene_id UUID REFERENCES scene(id) ON DELETE RESTRICT,
    sub_scene_id UUID REFERENCES sub_scene(id) ON DELETE RESTRICT,
    version INTEGER NOT NULL,
    template_version_id UUID REFERENCES agent_role_template_version(id) ON DELETE RESTRICT,
    enabled BOOLEAN,
    model_config_version_id UUID REFERENCES model_config_version(id) ON DELETE RESTRICT,
    skill_version_id UUID REFERENCES skill_version(id) ON DELETE RESTRICT,
    options JSONB,
    config_hash CHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (scope, scope_key, role, version),
    CONSTRAINT ck_agent_mount_role CHECK (role IN (
        'SCENE_EXPLORER', 'KNOWLEDGE_EXTRACTOR', 'ALIGNMENT_REVIEWER',
        'RULE_CATALOG_GENERATOR', 'DECISION_FLOW_GENERATOR', 'SKILL_PACKAGER', 'QA_EVALUATOR')),
    CONSTRAINT ck_agent_mount_scope CHECK (scope IN ('GLOBAL', 'SCENE', 'SUB_SCENE')),
    CONSTRAINT ck_agent_mount_version CHECK (version > 0),
    CONSTRAINT ck_agent_mount_hash CHECK (config_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_mount_options CHECK (options IS NULL OR jsonb_typeof(options) = 'object'),
    CONSTRAINT ck_agent_mount_scope_target CHECK (
        (scope = 'GLOBAL' AND scope_key = 'global' AND scene_id IS NULL AND sub_scene_id IS NULL)
        OR (scope = 'SCENE' AND scope_key = scene_id::text AND scene_id IS NOT NULL AND sub_scene_id IS NULL)
        OR (scope = 'SUB_SCENE' AND scope_key = sub_scene_id::text AND scene_id IS NOT NULL AND sub_scene_id IS NOT NULL)
    )
);

INSERT INTO agent_role_template_version (
    id, role, version, display_name, description, default_options, config_hash, created_at)
VALUES
('a0000000-0000-4000-8000-000000000001', 'SCENE_EXPLORER', 1, '场景探索智能体', '识别候选场景并整理素材边界', '{}'::jsonb, '6ffd8313ff6f662d20d2b0a0eb872a0735d3fee965a0cc80d6d55553501d2398', CURRENT_TIMESTAMP),
('a0000000-0000-4000-8000-000000000002', 'KNOWLEDGE_EXTRACTOR', 1, '知识萃取智能体', '从可信素材生成带来源的 KnowledgeIR', '{}'::jsonb, '5f9eac3e2b564dbabe711f5b0b80efd1b169b7233c27282e6bafd7436b50a32e', CURRENT_TIMESTAMP),
('a0000000-0000-4000-8000-000000000003', 'ALIGNMENT_REVIEWER', 1, '冲突检测与对齐智能体', '生成可审计的冲突与对齐提案', '{}'::jsonb, '62f1c6cf59f0ec15fb0bb68508a9d6ebf3437a9c1667bf52b65558593b37869d', CURRENT_TIMESTAMP),
('a0000000-0000-4000-8000-000000000004', 'RULE_CATALOG_GENERATOR', 1, '规则库生成智能体', '从定稿 Revision 生成规则清单', '{}'::jsonb, '33b29eda607affa46f0ed2ebe39f565f321e55ca96c3fafacbc61457072d925f', CURRENT_TIMESTAMP),
('a0000000-0000-4000-8000-000000000005', 'DECISION_FLOW_GENERATOR', 1, '思维链生成智能体', '生成可审计的研判流程，不暴露模型私有思维链', '{}'::jsonb, '5f5fb6edae0aaca738a6072a2377965e16f0f5f42a666578b3aa638e81f98c88', CURRENT_TIMESTAMP),
('a0000000-0000-4000-8000-000000000006', 'SKILL_PACKAGER', 1, 'Skill 生成智能体', '生成只包含提示词与资源的 Skill 包', '{}'::jsonb, '7be97aeec1c651d6b0ab49b011e5ba36461c442782d3101cf4541a8f37134562', CURRENT_TIMESTAMP),
('a0000000-0000-4000-8000-000000000007', 'QA_EVALUATOR', 1, 'QA 与评测集智能体', '生成 QA 数据并隔离留出集评测', '{}'::jsonb, 'f9316779590089aaed02303334866bd388532c2a2a98a17300f54c9c4c058db8', CURRENT_TIMESTAMP);

CREATE INDEX ix_agent_mount_resolution
    ON agent_mount_version (scope, scope_key, role, version DESC);

CREATE TABLE configuration_import (
    id UUID PRIMARY KEY,
    schema_version VARCHAR(16) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    scope_key VARCHAR(64) NOT NULL,
    scene_id UUID REFERENCES scene(id) ON DELETE RESTRICT,
    sub_scene_id UUID REFERENCES sub_scene(id) ON DELETE RESTRICT,
    base_etag CHAR(64) NOT NULL,
    manifest JSONB NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    diff JSONB NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_configuration_import_scope CHECK (scope IN ('GLOBAL', 'SCENE', 'SUB_SCENE')),
    CONSTRAINT ck_configuration_import_hash CHECK (manifest_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_configuration_import_base_etag CHECK (base_etag ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_configuration_import_target CHECK (
        (scope = 'GLOBAL' AND scope_key = 'global' AND scene_id IS NULL AND sub_scene_id IS NULL)
        OR (scope = 'SCENE' AND scope_key = scene_id::text AND scene_id IS NOT NULL AND sub_scene_id IS NULL)
        OR (scope = 'SUB_SCENE' AND scope_key = sub_scene_id::text AND scene_id IS NOT NULL AND sub_scene_id IS NOT NULL)
    )
);

CREATE TABLE configuration_import_application (
    import_id UUID PRIMARY KEY REFERENCES configuration_import(id) ON DELETE RESTRICT,
    applied_by UUID NOT NULL REFERENCES app_user(id),
    applied_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION reject_agent_governance_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER agent_role_template_version_immutable
    BEFORE UPDATE OR DELETE ON agent_role_template_version
    FOR EACH ROW EXECUTE FUNCTION reject_agent_governance_mutation();
CREATE TRIGGER agent_mount_version_immutable
    BEFORE UPDATE OR DELETE ON agent_mount_version
    FOR EACH ROW EXECUTE FUNCTION reject_agent_governance_mutation();
CREATE TRIGGER configuration_import_immutable
    BEFORE UPDATE OR DELETE ON configuration_import
    FOR EACH ROW EXECUTE FUNCTION reject_agent_governance_mutation();
CREATE TRIGGER configuration_import_application_immutable
    BEFORE UPDATE OR DELETE ON configuration_import_application
    FOR EACH ROW EXECUTE FUNCTION reject_agent_governance_mutation();

ALTER TABLE extraction_run
    ADD COLUMN role_config_hash CHAR(64),
    ADD CONSTRAINT fk_extraction_run_role_config
        FOREIGN KEY (role_config_version_id) REFERENCES agent_mount_version(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_extraction_run_role_config_pair CHECK (
        (role_config_version_id IS NULL AND role_config_hash IS NULL)
        OR (role_config_version_id IS NOT NULL AND role_config_hash ~ '^[0-9a-f]{64}$')
    );
