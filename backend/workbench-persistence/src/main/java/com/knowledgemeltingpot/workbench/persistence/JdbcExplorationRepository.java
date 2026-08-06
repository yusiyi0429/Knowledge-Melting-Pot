package com.knowledgemeltingpot.workbench.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.domain.ExplorationCandidate;
import com.knowledgemeltingpot.workbench.domain.ExplorationSession;
import com.knowledgemeltingpot.workbench.domain.ExplorationStatus;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcExplorationRepository implements ExplorationRepository {
    private static final String SESSION_COLUMNS = """
            SELECT id, title, status, explore_job_id, model_config_version_id, skill_version_id,
                   role_config_version_id, effective_config_hash, version, created_by, created_at, updated_at
            FROM exploration_session
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcExplorationRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExplorationSession insert(ExplorationSession session) {
        jdbc.sql("""
                INSERT INTO exploration_session (
                    id, title, status, explore_job_id, model_config_version_id, skill_version_id,
                    role_config_version_id, effective_config_hash, version, created_by, created_at, updated_at)
                VALUES (:id, :title, :status, :jobId, :modelId, :skillId, :roleId, :hash,
                    :version, :createdBy, :createdAt, :updatedAt)
                """)
                .param("id", session.id()).param("title", session.title()).param("status", session.status().name())
                .param("jobId", session.exploreJobId()).param("modelId", session.modelConfigVersionId())
                .param("skillId", session.skillVersionId()).param("roleId", session.roleConfigVersionId())
                .param("hash", session.effectiveConfigHash().isBlank() ? null : session.effectiveConfigHash())
                .param("version", session.version())
                .param("createdBy", session.createdBy()).param("createdAt", JdbcTimes.toJdbc(session.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(session.updatedAt())).update();
        return session;
    }

    @Override
    public Optional<ExplorationSession> find(UUID id) {
        return querySession(id, false);
    }

    @Override
    public Optional<ExplorationSession> lock(UUID id) {
        return querySession(id, true);
    }

    @Override
    public List<ExplorationSession> findRecent(int limit) {
        return jdbc.sql(SESSION_COLUMNS + " WHERE deleted_at IS NULL ORDER BY updated_at DESC, id LIMIT :limit")
                .param("limit", limit).query(JdbcExplorationRepository::mapSession).list();
    }

    @Override
    public boolean archive(UUID sessionId, int expectedVersion, UUID actorId, Instant archivedAt) {
        return jdbc.sql("""
                UPDATE exploration_session
                SET deleted_at = :archivedAt, deleted_by = :actorId,
                    version = version + 1, updated_at = :archivedAt
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                  AND status <> 'ANALYZING'
                """)
                .param("id", sessionId).param("expectedVersion", expectedVersion).param("actorId", actorId)
                .param("archivedAt", JdbcTimes.toJdbc(archivedAt)).update() == 1;
    }

    @Override
    public boolean linkMaterial(UUID sessionId, UUID materialId, Instant createdAt) {
        return jdbc.sql("""
                INSERT INTO exploration_material (session_id, material_id, ordinal, created_at)
                SELECT :sessionId, :materialId,
                       COALESCE((SELECT MAX(ordinal) + 1 FROM exploration_material WHERE session_id = :sessionId), 0),
                       :createdAt
                FROM exploration_session
                WHERE id = :sessionId AND status = 'DRAFT' AND deleted_at IS NULL
                ON CONFLICT (session_id, material_id) DO NOTHING
                """)
                .param("sessionId", sessionId).param("materialId", materialId)
                .param("createdAt", JdbcTimes.toJdbc(createdAt)).update() == 1;
    }

    @Override
    public List<Material> findMaterials(UUID sessionId) {
        return jdbc.sql("""
                SELECT m.id, m.file_name, m.file_format, m.media_type, m.object_key, m.sha256,
                       m.size_bytes, m.status, m.created_at, m.updated_at
                FROM exploration_material em
                JOIN material m ON m.id = em.material_id
                WHERE em.session_id = :sessionId
                ORDER BY em.ordinal, m.id
                """).param("sessionId", sessionId).query(JdbcExplorationRepository::mapMaterial).list();
    }

    @Override
    public boolean freezeRun(UUID sessionId, int expectedVersion, UUID jobId, UUID modelConfigVersionId,
            UUID skillVersionId, UUID roleConfigVersionId, String effectiveConfigHash, Instant updatedAt) {
        return jdbc.sql("""
                UPDATE exploration_session
                SET status = 'ANALYZING', explore_job_id = :jobId, model_config_version_id = :modelId,
                    skill_version_id = :skillId, role_config_version_id = :roleId,
                    effective_config_hash = :hash, version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND status = 'DRAFT' AND version = :expectedVersion
                """)
                .param("id", sessionId).param("expectedVersion", expectedVersion).param("jobId", jobId)
                .param("modelId", modelConfigVersionId).param("skillId", skillVersionId)
                .param("roleId", roleConfigVersionId).param("hash", effectiveConfigHash)
                .param("updatedAt", JdbcTimes.toJdbc(updatedAt)).update() == 1;
    }

    @Override
    @Transactional
    public boolean completeAnalysis(UUID sessionId, List<ExplorationCandidate> candidates, Instant updatedAt) {
        String status = jdbc.sql("""
                SELECT status FROM exploration_session
                WHERE id = :sessionId AND deleted_at IS NULL FOR UPDATE
                """)
                .param("sessionId", sessionId).query(String.class).optional().orElse("");
        if (!"ANALYZING".equals(status)) return false;
        Integer current = jdbc.sql("""
                SELECT COUNT(*) FROM exploration_candidate WHERE session_id = :sessionId
                """).param("sessionId", sessionId).query(Integer.class).single();
        if (current != null && current > 0) return false;
        for (ExplorationCandidate candidate : candidates) {
            jdbc.sql("""
                    INSERT INTO exploration_candidate (
                        id, session_id, rank, scene_name, scene_description, sub_scene_name,
                        sub_scene_description, rationale, value_level, estimated_rule_count,
                        estimated_flow_count, tags, created_at)
                    VALUES (:id, :sessionId, :rank, :sceneName, :sceneDescription, :subSceneName,
                        :subSceneDescription, :rationale, :valueLevel, :ruleCount, :flowCount,
                        CAST(:tags AS jsonb), :createdAt)
                    """)
                    .param("id", candidate.id()).param("sessionId", sessionId).param("rank", candidate.rank())
                    .param("sceneName", candidate.sceneName()).param("sceneDescription", candidate.sceneDescription())
                    .param("subSceneName", candidate.subSceneName())
                    .param("subSceneDescription", candidate.subSceneDescription())
                    .param("rationale", candidate.rationale()).param("valueLevel", candidate.valueLevel().name())
                    .param("ruleCount", candidate.estimatedRuleCount()).param("flowCount", candidate.estimatedFlowCount())
                    .param("tags", toJson(candidate.tags())).param("createdAt", JdbcTimes.toJdbc(candidate.createdAt()))
                    .update();
            for (UUID materialId : candidate.materialIds()) {
                jdbc.sql("""
                        INSERT INTO exploration_candidate_material (candidate_id, material_id)
                        VALUES (:candidateId, :materialId)
                        """).param("candidateId", candidate.id()).param("materialId", materialId).update();
            }
        }
        int updated = jdbc.sql("""
                UPDATE exploration_session SET status = 'READY', version = version + 1, updated_at = :updatedAt
                WHERE id = :sessionId AND status = 'ANALYZING'
                """).param("sessionId", sessionId).param("updatedAt", JdbcTimes.toJdbc(updatedAt)).update();
        if (updated != 1) throw new IllegalStateException("exploration analysis state changed inside transaction");
        return true;
    }

    @Override
    public List<ExplorationCandidate> findCandidates(UUID sessionId) {
        return jdbc.sql("""
                SELECT id, session_id, rank, scene_name, scene_description, sub_scene_name,
                       sub_scene_description, rationale, value_level, estimated_rule_count,
                       estimated_flow_count, tags, created_at
                FROM exploration_candidate WHERE session_id = :sessionId ORDER BY rank, id
                """).param("sessionId", sessionId).query(this::mapCandidate).list();
    }

    @Override
    public Optional<ExplorationCandidate> findCandidate(UUID sessionId, UUID candidateId) {
        return jdbc.sql("""
                SELECT id, session_id, rank, scene_name, scene_description, sub_scene_name,
                       sub_scene_description, rationale, value_level, estimated_rule_count,
                       estimated_flow_count, tags, created_at
                FROM exploration_candidate WHERE session_id = :sessionId AND id = :candidateId
                """).param("sessionId", sessionId).param("candidateId", candidateId)
                .query(this::mapCandidate).optional();
    }

    @Override
    public boolean transition(UUID sessionId, ExplorationStatus expected, ExplorationStatus target, Instant updatedAt) {
        return jdbc.sql("""
                UPDATE exploration_session SET status = :target, version = version + 1, updated_at = :updatedAt
                WHERE id = :id AND status = :expected AND deleted_at IS NULL
                """).param("id", sessionId).param("expected", expected.name()).param("target", target.name())
                .param("updatedAt", JdbcTimes.toJdbc(updatedAt)).update() == 1;
    }

    @Override
    @Transactional
    public boolean accept(UUID sessionId, int expectedVersion, UUID candidateId, UUID sceneId, UUID subSceneId,
            UUID roundId, UUID actorId, Instant acceptedAt) {
        int updated = jdbc.sql("""
                UPDATE exploration_session SET status = 'ACCEPTED', version = version + 1, updated_at = :acceptedAt
                WHERE id = :sessionId AND status = 'READY' AND version = :expectedVersion
                  AND deleted_at IS NULL
                """).param("sessionId", sessionId).param("expectedVersion", expectedVersion)
                .param("acceptedAt", JdbcTimes.toJdbc(acceptedAt)).update();
        if (updated != 1) return false;
        jdbc.sql("""
                INSERT INTO exploration_acceptance (
                    session_id, candidate_id, scene_id, sub_scene_id, round_id, accepted_by, accepted_at)
                VALUES (:sessionId, :candidateId, :sceneId, :subSceneId, :roundId, :actorId, :acceptedAt)
                """).param("sessionId", sessionId).param("candidateId", candidateId).param("sceneId", sceneId)
                .param("subSceneId", subSceneId).param("roundId", roundId).param("actorId", actorId)
                .param("acceptedAt", JdbcTimes.toJdbc(acceptedAt)).update();
        return true;
    }

    @Override
    public Optional<ExplorationAcceptance> findAcceptance(UUID sessionId) {
        return jdbc.sql("""
                SELECT session_id, candidate_id, scene_id, sub_scene_id, round_id, accepted_by, accepted_at
                FROM exploration_acceptance WHERE session_id = :sessionId
                """).param("sessionId", sessionId).query((rs, row) -> new ExplorationAcceptance(
                        rs.getObject("session_id", UUID.class), rs.getObject("candidate_id", UUID.class),
                        rs.getObject("scene_id", UUID.class), rs.getObject("sub_scene_id", UUID.class),
                        rs.getObject("round_id", UUID.class), rs.getObject("accepted_by", UUID.class),
                        rs.getTimestamp("accepted_at").toInstant())).optional();
    }

    private Optional<ExplorationSession> querySession(UUID id, boolean lock) {
        return jdbc.sql(SESSION_COLUMNS + " WHERE id = :id AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(JdbcExplorationRepository::mapSession).optional();
    }

    private ExplorationCandidate mapCandidate(ResultSet rs, int row) throws SQLException {
        UUID candidateId = rs.getObject("id", UUID.class);
        List<String> tags;
        try {
            tags = objectMapper.readValue(rs.getString("tags"), new TypeReference<>() { });
        } catch (com.fasterxml.jackson.core.JacksonException exception) {
            throw new SQLException("invalid exploration candidate tags", exception);
        }
        List<UUID> materialIds = jdbc.sql("""
                SELECT material_id FROM exploration_candidate_material
                WHERE candidate_id = :candidateId ORDER BY material_id
                """).param("candidateId", candidateId).query(UUID.class).list();
        return new ExplorationCandidate(candidateId, rs.getObject("session_id", UUID.class), rs.getInt("rank"),
                rs.getString("scene_name"), rs.getString("scene_description"), rs.getString("sub_scene_name"),
                rs.getString("sub_scene_description"), rs.getString("rationale"),
                ExplorationCandidate.ValueLevel.valueOf(rs.getString("value_level")),
                rs.getInt("estimated_rule_count"), rs.getInt("estimated_flow_count"), tags, materialIds,
                rs.getTimestamp("created_at").toInstant());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("exploration value cannot be serialized", exception);
        }
    }

    private static ExplorationSession mapSession(ResultSet rs, int row) throws SQLException {
        return new ExplorationSession(rs.getObject("id", UUID.class), rs.getString("title"),
                ExplorationStatus.valueOf(rs.getString("status")), rs.getObject("explore_job_id", UUID.class),
                rs.getObject("model_config_version_id", UUID.class), rs.getObject("skill_version_id", UUID.class),
                rs.getObject("role_config_version_id", UUID.class), value(rs, "effective_config_hash"),
                rs.getInt("version"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static Material mapMaterial(ResultSet rs, int row) throws SQLException {
        return new Material(rs.getObject("id", UUID.class), rs.getString("file_name"),
                MaterialFormat.valueOf(rs.getString("file_format")), rs.getString("media_type"),
                rs.getString("object_key"), rs.getString("sha256").trim(), rs.getLong("size_bytes"),
                MaterialStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String value(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value.trim();
    }
}
