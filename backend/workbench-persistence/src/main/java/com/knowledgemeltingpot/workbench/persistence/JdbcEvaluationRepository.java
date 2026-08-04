package com.knowledgemeltingpot.workbench.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.EvaluationRepository;
import com.knowledgemeltingpot.workbench.domain.EvaluationCase;
import com.knowledgemeltingpot.workbench.domain.EvaluationCaseResult;
import com.knowledgemeltingpot.workbench.domain.EvaluationOutcome;
import com.knowledgemeltingpot.workbench.domain.EvaluationRun;
import com.knowledgemeltingpot.workbench.domain.EvaluationStatus;
import java.math.BigDecimal;
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
public class JdbcEvaluationRepository implements EvaluationRepository {
    private static final String RUN_COLUMNS = """
            SELECT id, release_id, sub_scene_id, round_id, document_revision_id,
                   evaluation_asset_id, skill_asset_id, model_config_version_id, skill_version_id,
                   job_id, case_set_hash, status, total_cases, passed_cases, failed_cases, error_cases,
                   accuracy, failure_code, created_by, created_at, started_at, completed_at, updated_at
            FROM evaluation_run
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEvaluationRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public EvaluationRun insert(EvaluationRun run) {
        jdbc.sql("""
                INSERT INTO evaluation_run (
                    id, release_id, sub_scene_id, round_id, document_revision_id,
                    evaluation_asset_id, skill_asset_id, model_config_version_id, skill_version_id,
                    job_id, case_set_hash, status, total_cases, passed_cases, failed_cases, error_cases,
                    accuracy, failure_code, created_by, created_at, started_at, completed_at, updated_at)
                VALUES (:id, :releaseId, :subSceneId, :roundId, :revisionId,
                    :evaluationAssetId, :skillAssetId, :modelId, :skillId,
                    :jobId, NULL, :status, 0, 0, 0, 0, NULL, '', :createdBy,
                    :createdAt, NULL, NULL, :updatedAt)
                """).param("id", run.id()).param("releaseId", run.releaseId())
                .param("subSceneId", run.subSceneId()).param("roundId", run.roundId())
                .param("revisionId", run.documentRevisionId()).param("evaluationAssetId", run.evaluationAssetId())
                .param("skillAssetId", run.skillAssetId()).param("modelId", run.modelConfigVersionId())
                .param("skillId", run.skillVersionId()).param("jobId", run.jobId())
                .param("status", run.status().name()).param("createdBy", run.createdBy())
                .param("createdAt", JdbcTimes.toJdbc(run.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(run.updatedAt())).update();
        return run;
    }

    @Override
    public Optional<EvaluationRun> find(UUID runId) {
        return jdbc.sql(RUN_COLUMNS + " WHERE id = :id").param("id", runId)
                .query(JdbcEvaluationRepository::mapRun).optional();
    }

    @Override
    public List<EvaluationRun> findRecent(UUID releaseId, UUID subSceneId, int limit) {
        return jdbc.sql(RUN_COLUMNS + """
                 WHERE release_id = :releaseId AND sub_scene_id = :subSceneId
                 ORDER BY created_at DESC, id DESC LIMIT :limit
                """).param("releaseId", releaseId).param("subSceneId", subSceneId).param("limit", limit)
                .query(JdbcEvaluationRepository::mapRun).list();
    }

    @Override
    public boolean markRunning(UUID runId, UUID jobId, Instant startedAt) {
        return jdbc.sql("""
                UPDATE evaluation_run
                SET status = 'RUNNING', failure_code = '', completed_at = NULL,
                    started_at = COALESCE(started_at, :startedAt), updated_at = :startedAt
                WHERE id = :id AND job_id = :jobId AND status IN ('QUEUED', 'RUNNING', 'FAILED')
                """).param("id", runId).param("jobId", jobId)
                .param("startedAt", JdbcTimes.toJdbc(startedAt)).update() == 1;
    }

    @Override
    @Transactional
    public boolean insertCaseSet(UUID runId, String caseSetHash, List<EvaluationCase> cases, Instant updatedAt) {
        String status = jdbc.sql("SELECT status FROM evaluation_run WHERE id = :id FOR UPDATE")
                .param("id", runId).query(String.class).optional().orElse("");
        if (!"RUNNING".equals(status)) return false;
        Integer existing = jdbc.sql("SELECT COUNT(*) FROM evaluation_case WHERE evaluation_run_id = :id")
                .param("id", runId).query(Integer.class).single();
        if (existing != null && existing > 0) return false;
        for (EvaluationCase evaluationCase : cases) {
            jdbc.sql("""
                    INSERT INTO evaluation_case (
                        id, evaluation_run_id, ordinal, case_key, input_text, expected_text,
                        material_id, chunk_id, source_ref_code, content_hash, tags, created_at)
                    VALUES (:id, :runId, :ordinal, :caseKey, :input, :expected,
                        :materialId, :chunkId, :sourceRefCode, :contentHash, CAST(:tags AS jsonb), :createdAt)
                    """).param("id", evaluationCase.id()).param("runId", runId)
                    .param("ordinal", evaluationCase.ordinal()).param("caseKey", evaluationCase.caseKey())
                    .param("input", evaluationCase.input()).param("expected", evaluationCase.expected())
                    .param("materialId", evaluationCase.materialId()).param("chunkId", evaluationCase.chunkId())
                    .param("sourceRefCode", evaluationCase.sourceRefCode())
                    .param("contentHash", evaluationCase.contentHash())
                    .param("tags", toJson(evaluationCase.tags()))
                    .param("createdAt", JdbcTimes.toJdbc(evaluationCase.createdAt())).update();
        }
        int updated = jdbc.sql("""
                UPDATE evaluation_run SET case_set_hash = :hash, total_cases = :total, updated_at = :updatedAt
                WHERE id = :id AND status = 'RUNNING' AND total_cases = 0
                """).param("id", runId).param("hash", caseSetHash).param("total", cases.size())
                .param("updatedAt", JdbcTimes.toJdbc(updatedAt)).update();
        if (updated != 1) throw new IllegalStateException("evaluation case snapshot changed inside transaction");
        return true;
    }

    @Override
    public List<EvaluationCase> findCases(UUID runId) {
        return jdbc.sql("""
                SELECT id, evaluation_run_id, ordinal, case_key, input_text, expected_text,
                       material_id, chunk_id, source_ref_code, content_hash, tags, created_at
                FROM evaluation_case WHERE evaluation_run_id = :runId ORDER BY ordinal, id
                """).param("runId", runId).query(this::mapCase).list();
    }

    @Override
    public List<EvaluationCaseResult> findResults(UUID runId) {
        return jdbc.sql("""
                SELECT evaluation_run_id, case_id, prediction, outcome, error_code, latency_millis, created_at
                FROM evaluation_case_result WHERE evaluation_run_id = :runId ORDER BY created_at, case_id
                """).param("runId", runId).query(JdbcEvaluationRepository::mapResult).list();
    }

    @Override
    public boolean insertResult(EvaluationCaseResult result) {
        return jdbc.sql("""
                INSERT INTO evaluation_case_result (
                    case_id, evaluation_run_id, prediction, outcome, error_code, latency_millis, created_at)
                VALUES (:caseId, :runId, :prediction, :outcome, :errorCode, :latency, :createdAt)
                ON CONFLICT (case_id) DO NOTHING
                """).param("caseId", result.caseId()).param("runId", result.evaluationRunId())
                .param("prediction", result.prediction()).param("outcome", result.outcome().name())
                .param("errorCode", result.errorCode()).param("latency", result.latencyMillis())
                .param("createdAt", JdbcTimes.toJdbc(result.createdAt())).update() == 1;
    }

    @Override
    public EvaluationCounts counts(UUID runId) {
        return jdbc.sql("""
                SELECT COUNT(c.id) AS total,
                       COUNT(r.case_id) FILTER (WHERE r.outcome = 'PASSED') AS passed,
                       COUNT(r.case_id) FILTER (WHERE r.outcome = 'FAILED') AS failed,
                       COUNT(r.case_id) FILTER (WHERE r.outcome = 'ERROR') AS errors
                FROM evaluation_case c
                LEFT JOIN evaluation_case_result r ON r.case_id = c.id
                WHERE c.evaluation_run_id = :runId
                """).param("runId", runId).query((rs, row) -> new EvaluationCounts(
                        rs.getInt("total"), rs.getInt("passed"), rs.getInt("failed"), rs.getInt("errors")))
                .single();
    }

    @Override
    public boolean markSucceeded(UUID runId, EvaluationCounts counts, BigDecimal accuracy, Instant completedAt) {
        return jdbc.sql("""
                UPDATE evaluation_run
                SET status = 'SUCCEEDED', total_cases = :total, passed_cases = :passed,
                    failed_cases = :failed, error_cases = :errors, accuracy = :accuracy,
                    failure_code = '', completed_at = :completedAt, updated_at = :completedAt
                WHERE id = :id AND status = 'RUNNING'
                """).param("id", runId).param("total", counts.total()).param("passed", counts.passed())
                .param("failed", counts.failed()).param("errors", counts.errors()).param("accuracy", accuracy)
                .param("completedAt", JdbcTimes.toJdbc(completedAt)).update() == 1;
    }

    @Override
    public boolean markFailed(UUID runId, String failureCode, Instant completedAt) {
        return jdbc.sql("""
                UPDATE evaluation_run
                SET status = 'FAILED', failure_code = :failureCode,
                    completed_at = :completedAt, updated_at = :completedAt
                WHERE id = :id AND status IN ('QUEUED', 'RUNNING', 'FAILED')
                """).param("id", runId).param("failureCode", failureCode)
                .param("completedAt", JdbcTimes.toJdbc(completedAt)).update() == 1;
    }

    @Override
    public boolean markCancelled(UUID runId, Instant completedAt) {
        return jdbc.sql("""
                UPDATE evaluation_run
                SET status = 'CANCELLED', failure_code = '',
                    completed_at = :completedAt, updated_at = :completedAt
                WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
                """).param("id", runId).param("completedAt", JdbcTimes.toJdbc(completedAt)).update() == 1;
    }

    private EvaluationCase mapCase(ResultSet rs, int row) throws SQLException {
        List<String> tags;
        try {
            tags = objectMapper.readValue(rs.getString("tags"), new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new SQLException("persisted evaluation tags are invalid", exception);
        }
        return new EvaluationCase(rs.getObject("id", UUID.class), rs.getObject("evaluation_run_id", UUID.class),
                rs.getInt("ordinal"), rs.getString("case_key"), rs.getString("input_text"),
                rs.getString("expected_text"), rs.getObject("material_id", UUID.class),
                rs.getObject("chunk_id", UUID.class), rs.getString("source_ref_code"),
                rs.getString("content_hash"), tags, rs.getTimestamp("created_at").toInstant());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("evaluation metadata cannot be serialized", exception);
        }
    }

    private static EvaluationRun mapRun(ResultSet rs, int row) throws SQLException {
        var accuracy = rs.getBigDecimal("accuracy");
        var startedAt = rs.getTimestamp("started_at");
        var completedAt = rs.getTimestamp("completed_at");
        return new EvaluationRun(rs.getObject("id", UUID.class), rs.getObject("release_id", UUID.class),
                rs.getObject("sub_scene_id", UUID.class), rs.getObject("round_id", UUID.class),
                rs.getObject("document_revision_id", UUID.class), rs.getObject("evaluation_asset_id", UUID.class),
                rs.getObject("skill_asset_id", UUID.class), rs.getObject("model_config_version_id", UUID.class),
                rs.getObject("skill_version_id", UUID.class), rs.getObject("job_id", UUID.class),
                nullable(rs, "case_set_hash"), EvaluationStatus.valueOf(rs.getString("status")),
                rs.getInt("total_cases"), rs.getInt("passed_cases"), rs.getInt("failed_cases"),
                rs.getInt("error_cases"), accuracy, rs.getString("failure_code"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                startedAt == null ? null : startedAt.toInstant(), completedAt == null ? null : completedAt.toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static EvaluationCaseResult mapResult(ResultSet rs, int row) throws SQLException {
        return new EvaluationCaseResult(rs.getObject("evaluation_run_id", UUID.class),
                rs.getObject("case_id", UUID.class), rs.getString("prediction"),
                EvaluationOutcome.valueOf(rs.getString("outcome")), rs.getString("error_code"),
                rs.getLong("latency_millis"), rs.getTimestamp("created_at").toInstant());
    }

    private static String nullable(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }
}
