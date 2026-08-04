package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.AgentMountRepository;
import com.knowledgemeltingpot.workbench.domain.AgentMountScope;
import com.knowledgemeltingpot.workbench.domain.AgentMountVersion;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.AgentRoleTemplateVersion;
import com.knowledgemeltingpot.workbench.domain.ConfigurationImportPreview;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentMountRepository implements AgentMountRepository {
    private final JdbcClient jdbc;

    public JdbcAgentMountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockScope(AgentMountScope scope, UUID scopeId) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtext(:key))")
                .param("key", scopeKey(scope, scopeId))
                .query(Integer.class)
                .single();
    }

    @Override
    public List<AgentMountVersion> findLatest(AgentMountScope scope, UUID scopeId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (role) *
                FROM agent_mount_version
                WHERE scope = :scope AND scope_key = :scopeKey
                ORDER BY role, version DESC
                """)
                .param("scope", scope.name())
                .param("scopeKey", scopeKey(scope, scopeId))
                .query(JdbcAgentMountRepository::mapMount)
                .list();
    }

    @Override
    public Optional<AgentMountVersion> findLatest(AgentMountScope scope, UUID scopeId, AgentRole role) {
        return jdbc.sql("""
                SELECT * FROM agent_mount_version
                WHERE scope = :scope AND scope_key = :scopeKey AND role = :role
                ORDER BY version DESC LIMIT 1
                """)
                .param("scope", scope.name())
                .param("scopeKey", scopeKey(scope, scopeId))
                .param("role", role.name())
                .query(JdbcAgentMountRepository::mapMount)
                .optional();
    }

    @Override
    public Optional<AgentMountVersion> findVersion(UUID versionId) {
        return jdbc.sql("SELECT * FROM agent_mount_version WHERE id = :id")
                .param("id", versionId)
                .query(JdbcAgentMountRepository::mapMount)
                .optional();
    }

    @Override
    public AgentMountVersion insert(AgentMountVersion version, UUID sceneId) {
        jdbc.sql("""
                INSERT INTO agent_mount_version (
                    id, role, scope, scope_key, scene_id, sub_scene_id, version,
                    template_version_id, enabled, model_config_version_id, skill_version_id,
                    options, config_hash, created_by, created_at)
                VALUES (
                    :id, :role, :scope, :scopeKey, :sceneId, :subSceneId, :version,
                    :templateVersionId, :enabled, :modelVersionId, :skillVersionId,
                    CAST(:options AS jsonb), :configHash, :createdBy, :createdAt)
                """)
                .param("id", version.id())
                .param("role", version.role().name())
                .param("scope", version.scope().name())
                .param("scopeKey", scopeKey(version.scope(), version.scopeId()))
                .param("sceneId", version.scope() == AgentMountScope.GLOBAL ? null : sceneId)
                .param("subSceneId", version.scope() == AgentMountScope.SUB_SCENE ? version.scopeId() : null)
                .param("version", version.version())
                .param("templateVersionId", version.templateVersionId())
                .param("enabled", version.enabled())
                .param("modelVersionId", version.modelConfigVersionId())
                .param("skillVersionId", version.skillVersionId())
                .param("options", version.optionsJson())
                .param("configHash", version.configHash())
                .param("createdBy", version.createdBy())
                .param("createdAt", JdbcTimes.toJdbc(version.createdAt()))
                .update();
        return version;
    }

    @Override
    public List<AgentRoleTemplateVersion> findLatestTemplates() {
        return jdbc.sql("""
                SELECT DISTINCT ON (role) * FROM agent_role_template_version
                ORDER BY role, version DESC
                """)
                .query((resultSet, rowNumber) -> new AgentRoleTemplateVersion(
                        resultSet.getObject("id", UUID.class), AgentRole.valueOf(resultSet.getString("role")),
                        resultSet.getInt("version"), resultSet.getString("display_name"),
                        resultSet.getString("description"), resultSet.getString("default_options"),
                        resultSet.getString("config_hash").trim(), resultSet.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public ConfigurationImportPreview insertImport(ConfigurationImportPreview preview) {
        jdbc.sql("""
                INSERT INTO configuration_import (
                    id, schema_version, scope, scope_key, scene_id, sub_scene_id, base_etag,
                    manifest, manifest_hash, diff, created_by, created_at)
                VALUES (
                    :id, :schemaVersion, :scope, :scopeKey, :sceneId, :subSceneId, :baseEtag,
                    CAST(:manifest AS jsonb), :manifestHash, CAST(:diff AS jsonb), :createdBy, :createdAt)
                """)
                .param("id", preview.id())
                .param("schemaVersion", preview.schemaVersion())
                .param("scope", preview.scope().name())
                .param("scopeKey", scopeKey(preview.scope(), preview.scopeId()))
                .param("sceneId", preview.scope() == AgentMountScope.GLOBAL ? null : preview.sceneId())
                .param("subSceneId", preview.scope() == AgentMountScope.SUB_SCENE ? preview.scopeId() : null)
                .param("baseEtag", preview.baseEtag())
                .param("manifest", preview.manifestJson())
                .param("manifestHash", preview.manifestHash())
                .param("diff", preview.diffJson())
                .param("createdBy", preview.createdBy())
                .param("createdAt", JdbcTimes.toJdbc(preview.createdAt()))
                .update();
        return preview;
    }

    @Override
    public Optional<ConfigurationImportPreview> findImport(UUID importId) {
        return jdbc.sql("""
                SELECT i.*, a.applied_by, a.applied_at
                FROM configuration_import i
                LEFT JOIN configuration_import_application a ON a.import_id = i.id
                WHERE i.id = :id
                """)
                .param("id", importId)
                .query(JdbcAgentMountRepository::mapImport)
                .optional();
    }

    @Override
    public boolean markImportApplied(UUID importId, UUID actorId, Instant appliedAt) {
        return jdbc.sql("""
                INSERT INTO configuration_import_application (import_id, applied_by, applied_at)
                VALUES (:importId, :actorId, :appliedAt)
                ON CONFLICT (import_id) DO NOTHING
                """)
                .param("importId", importId)
                .param("actorId", actorId)
                .param("appliedAt", JdbcTimes.toJdbc(appliedAt))
                .update() == 1;
    }

    private static AgentMountVersion mapMount(ResultSet resultSet, int rowNumber) throws SQLException {
        AgentMountScope scope = AgentMountScope.valueOf(resultSet.getString("scope"));
        UUID scopeId = switch (scope) {
            case GLOBAL -> null;
            case SCENE -> resultSet.getObject("scene_id", UUID.class);
            case SUB_SCENE -> resultSet.getObject("sub_scene_id", UUID.class);
        };
        return new AgentMountVersion(resultSet.getObject("id", UUID.class),
                AgentRole.valueOf(resultSet.getString("role")), scope, scopeId, resultSet.getInt("version"),
                resultSet.getObject("template_version_id", UUID.class), (Boolean) resultSet.getObject("enabled"),
                resultSet.getObject("model_config_version_id", UUID.class),
                resultSet.getObject("skill_version_id", UUID.class), resultSet.getString("options"),
                resultSet.getString("config_hash").trim(), resultSet.getObject("created_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static ConfigurationImportPreview mapImport(ResultSet resultSet, int rowNumber) throws SQLException {
        AgentMountScope scope = AgentMountScope.valueOf(resultSet.getString("scope"));
        UUID scopeId = switch (scope) {
            case GLOBAL -> null;
            case SCENE -> resultSet.getObject("scene_id", UUID.class);
            case SUB_SCENE -> resultSet.getObject("sub_scene_id", UUID.class);
        };
        var appliedTimestamp = resultSet.getTimestamp("applied_at");
        return new ConfigurationImportPreview(resultSet.getObject("id", UUID.class),
                resultSet.getString("schema_version"), scope, scopeId,
                resultSet.getObject("scene_id", UUID.class), resultSet.getString("base_etag").trim(),
                resultSet.getString("manifest"), resultSet.getString("manifest_hash").trim(),
                resultSet.getString("diff"), resultSet.getObject("created_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getObject("applied_by", UUID.class),
                appliedTimestamp == null ? null : appliedTimestamp.toInstant());
    }

    private static String scopeKey(AgentMountScope scope, UUID scopeId) {
        return scope == AgentMountScope.GLOBAL ? "global" : scopeId.toString();
    }
}
