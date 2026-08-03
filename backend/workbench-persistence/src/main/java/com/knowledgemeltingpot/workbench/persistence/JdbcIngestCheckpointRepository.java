package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.IngestCheckpointRepository;
import com.knowledgemeltingpot.workbench.domain.IngestStage;
import com.knowledgemeltingpot.workbench.domain.MaterialIngestAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIngestCheckpointRepository implements IngestCheckpointRepository {

    private final JdbcClient jdbc;

    public JdbcIngestCheckpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MaterialIngestAttempt startAttempt(MaterialIngestAttempt attempt) {
        jdbc.sql("""
                INSERT INTO material_ingest_attempt (
                    job_id, material_id, attempt, stage, failure_code, retryable, started_at,
                    completed_at, scan_engine_version, scan_signature_version, parser_name, parser_version)
                VALUES (
                    :jobId, :materialId, :attempt, :stage, NULL, FALSE, :startedAt,
                    NULL, NULL, NULL, NULL, NULL)
                """)
                .param("jobId", attempt.jobId())
                .param("materialId", attempt.materialId())
                .param("attempt", attempt.attempt())
                .param("stage", attempt.stage().name())
                .param("startedAt", JdbcTimes.toJdbc(attempt.startedAt()))
                .update();
        return attempt;
    }

    @Override
    public void reopenAttempt(UUID jobId, int attempt, Instant startedAt) {
        jdbc.sql("""
                UPDATE material_ingest_attempt
                SET attempt = :attempt, stage = 'STARTED', failure_code = NULL, retryable = FALSE,
                    completed_at = NULL, started_at = :startedAt,
                    scan_engine_version = NULL, scan_signature_version = NULL,
                    parser_name = NULL, parser_version = NULL
                WHERE job_id = :jobId
                """)
                .param("attempt", attempt)
                .param("startedAt", JdbcTimes.toJdbc(startedAt))
                .param("jobId", jobId)
                .update();
    }

    @Override
    public void updateStage(UUID jobId, IngestStage stage) {
        jdbc.sql("UPDATE material_ingest_attempt SET stage = :stage WHERE job_id = :jobId")
                .param("stage", stage.name())
                .param("jobId", jobId)
                .update();
    }

    @Override
    public void completeAttempt(UUID jobId, IngestStage stage, String parserName, String parserVersion,
            Instant completedAt) {
        jdbc.sql("""
                UPDATE material_ingest_attempt
                SET stage = :stage, parser_name = :parserName, parser_version = :parserVersion,
                    completed_at = :completedAt
                WHERE job_id = :jobId
                """)
                .param("stage", stage.name())
                .param("parserName", parserName == null ? "" : parserName)
                .param("parserVersion", parserVersion == null ? "" : parserVersion)
                .param("completedAt", JdbcTimes.toJdbc(completedAt))
                .param("jobId", jobId)
                .update();
    }

    @Override
    public void failAttempt(UUID jobId, IngestStage stage, String failureCode, boolean retryable,
            Instant completedAt) {
        jdbc.sql("""
                UPDATE material_ingest_attempt
                SET stage = :stage, failure_code = :failureCode, retryable = :retryable,
                    completed_at = :completedAt
                WHERE job_id = :jobId
                """)
                .param("stage", stage.name())
                .param("failureCode", failureCode)
                .param("retryable", retryable)
                .param("completedAt", JdbcTimes.toJdbc(completedAt))
                .param("jobId", jobId)
                .update();
    }

    @Override
    public Optional<MaterialIngestAttempt> findLatestAttempt(UUID materialId) {
        return jdbc.sql("""
                SELECT job_id, material_id, attempt, stage, failure_code, retryable, started_at,
                       completed_at, scan_engine_version, scan_signature_version, parser_name, parser_version
                FROM material_ingest_attempt
                WHERE material_id = :materialId
                ORDER BY attempt DESC
                LIMIT 1
                """)
                .param("materialId", materialId)
                .query(JdbcIngestCheckpointRepository::mapAttempt)
                .optional();
    }

    @Override
    public Optional<MaterialIngestAttempt> findByJobId(UUID jobId) {
        return jdbc.sql("""
                SELECT job_id, material_id, attempt, stage, failure_code, retryable, started_at,
                       completed_at, scan_engine_version, scan_signature_version, parser_name, parser_version
                FROM material_ingest_attempt
                WHERE job_id = :jobId
                """)
                .param("jobId", jobId)
                .query(JdbcIngestCheckpointRepository::mapAttempt)
                .optional();
    }

    private static MaterialIngestAttempt mapAttempt(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp completedAt = resultSet.getTimestamp("completed_at");
        return new MaterialIngestAttempt(
                resultSet.getObject("job_id", UUID.class),
                resultSet.getObject("material_id", UUID.class),
                resultSet.getInt("attempt"),
                IngestStage.valueOf(resultSet.getString("stage")),
                resultSet.getString("failure_code"),
                resultSet.getBoolean("retryable"),
                resultSet.getTimestamp("started_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                resultSet.getString("scan_engine_version"),
                resultSet.getString("scan_signature_version"),
                resultSet.getString("parser_name"),
                resultSet.getString("parser_version"));
    }
}
