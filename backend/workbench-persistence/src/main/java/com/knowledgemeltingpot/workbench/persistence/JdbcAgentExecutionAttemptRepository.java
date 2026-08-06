package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.AgentExecutionAttemptRepository;
import com.knowledgemeltingpot.workbench.domain.AgentExecutionAttempt;
import com.knowledgemeltingpot.workbench.domain.AgentExecutionAttemptStatus;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentExecutionAttemptRepository implements AgentExecutionAttemptRepository {
    private static final String COLUMNS = """
            SELECT id, job_id, job_attempt, role, asset_type, asset_id, model_config_version_id, skill_version_id,
                   role_config_version_id, effective_config_hash, input_hash, output_hash, status,
                   failure_code, started_at, completed_at
            FROM agent_execution_attempt
            """;
    private final JdbcClient jdbc;

    public JdbcAgentExecutionAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AgentExecutionAttempt insert(AgentExecutionAttempt attempt) {
        jdbc.sql("""
                INSERT INTO agent_execution_attempt (id, job_id, job_attempt, role, asset_type, asset_id,
                    model_config_version_id, skill_version_id, role_config_version_id,
                    effective_config_hash, input_hash, output_hash, status, failure_code, started_at, completed_at)
                VALUES (:id, :jobId, :jobAttempt, :role, :assetType, :assetId, :modelId, :skillId, :roleConfigId,
                    :configHash, :inputHash, :outputHash, :status, :failureCode, :startedAt, :completedAt)
                """).param("id", attempt.id()).param("jobId", attempt.jobId())
                .param("jobAttempt", attempt.jobAttempt()).param("role", attempt.role().name())
                .param("assetType", attempt.assetType().name()).param("assetId", attempt.assetId())
                .param("modelId", attempt.modelConfigVersionId())
                .param("skillId", attempt.skillVersionId()).param("roleConfigId", attempt.roleConfigVersionId())
                .param("configHash", attempt.effectiveConfigHash()).param("inputHash", attempt.inputHash())
                .param("outputHash", attempt.outputHash()).param("status", attempt.status().name())
                .param("failureCode", attempt.failureCode()).param("startedAt", JdbcTimes.toJdbc(attempt.startedAt()))
                .param("completedAt", JdbcTimes.toJdbc(attempt.completedAt())).update();
        return attempt;
    }

    @Override
    public boolean markSucceeded(UUID attemptId, String outputHash, Instant completedAt) {
        return jdbc.sql("""
                UPDATE agent_execution_attempt SET status = 'SUCCEEDED', output_hash = :outputHash,
                    failure_code = '', completed_at = :completedAt
                WHERE id = :id AND status = 'RUNNING'
                """).param("id", attemptId).param("outputHash", outputHash)
                .param("completedAt", JdbcTimes.toJdbc(completedAt)).update() == 1;
    }

    @Override
    public boolean markFailed(UUID attemptId, String failureCode, Instant completedAt) {
        return jdbc.sql("""
                UPDATE agent_execution_attempt SET status = 'FAILED', failure_code = :failureCode,
                    completed_at = :completedAt WHERE id = :id AND status = 'RUNNING'
                """).param("id", attemptId).param("failureCode", failureCode)
                .param("completedAt", JdbcTimes.toJdbc(completedAt)).update() == 1;
    }

    @Override
    public List<AgentExecutionAttempt> findByJob(UUID jobId) {
        return jdbc.sql(COLUMNS + " WHERE job_id = :jobId ORDER BY started_at, asset_type")
                .param("jobId", jobId).query(JdbcAgentExecutionAttemptRepository::map).list();
    }

    @Override
    public java.util.Optional<AgentExecutionAttempt> findByAsset(UUID assetId) {
        return jdbc.sql(COLUMNS + " WHERE asset_id = :assetId")
                .param("assetId", assetId).query(JdbcAgentExecutionAttemptRepository::map).optional();
    }

    private static AgentExecutionAttempt map(ResultSet rs, int row) throws SQLException {
        return new AgentExecutionAttempt(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getInt("job_attempt"), AgentRole.valueOf(rs.getString("role")),
                AssetType.valueOf(rs.getString("asset_type")), rs.getObject("asset_id", UUID.class),
                rs.getObject("model_config_version_id", UUID.class),
                rs.getObject("skill_version_id", UUID.class), rs.getObject("role_config_version_id", UUID.class),
                rs.getString("effective_config_hash"), rs.getString("input_hash"), rs.getString("output_hash"),
                AgentExecutionAttemptStatus.valueOf(rs.getString("status")), rs.getString("failure_code"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                rs.getObject("completed_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("completed_at", OffsetDateTime.class).toInstant());
    }
}
