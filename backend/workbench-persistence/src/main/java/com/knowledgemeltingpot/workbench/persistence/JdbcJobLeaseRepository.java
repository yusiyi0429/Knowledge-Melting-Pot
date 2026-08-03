package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.JobLeaseRepository;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJobLeaseRepository implements JobLeaseRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcJobLeaseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LeasedJob> claimNext(String workerId, Set<JobType> acceptedTypes,
            Duration leaseDuration, Instant now) {
        if (acceptedTypes.isEmpty()) {
            return Optional.empty();
        }
        List<String> types = acceptedTypes.stream().map(Enum::name).toList();
        List<LeasedJob> rows = jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM job
                    WHERE cancel_requested = FALSE
                      AND job_type IN (:types)
                      AND (status = 'QUEUED' OR (status = 'RUNNING' AND lease_until < :now))
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE job j
                SET status = 'RUNNING', locked_by = :workerId, lease_until = :leaseUntil,
                    attempt = j.attempt + 1, progress = GREATEST(j.progress, 1), updated_at = :now
                FROM candidate c
                WHERE j.id = c.id
                RETURNING j.id, j.job_type, j.aggregate_type, j.aggregate_id, j.status, j.progress,
                          j.payload, j.result_reference, j.error_code, j.error_message, j.requested_by,
                          j.created_at, j.updated_at, j.locked_by, j.lease_until, j.attempt
                """, Map.of(
                        "types", types,
                        "workerId", workerId,
                        "now", JdbcTimes.toJdbc(now),
                        "leaseUntil", JdbcTimes.toJdbc(now.plus(leaseDuration))), JdbcJobLeaseRepository::mapLeasedJob);
        return rows.stream().findFirst();
    }

    @Override
    public boolean renew(UUID jobId, String workerId, Duration leaseDuration, Instant now) {
        return jdbc.update("""
                UPDATE job SET lease_until = :leaseUntil, updated_at = :now
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
                  AND cancel_requested = FALSE
                """, Map.of("id", jobId, "workerId", workerId, "now", JdbcTimes.toJdbc(now),
                "leaseUntil", JdbcTimes.toJdbc(now.plus(leaseDuration)))) == 1;
    }

    @Override
    public boolean updateProgress(UUID jobId, String workerId, int progress, Instant now) {
        if (progress < 1 || progress > 99) {
            throw new IllegalArgumentException("running progress must be between 1 and 99");
        }
        return jdbc.update("""
                UPDATE job SET progress = GREATEST(progress, :progress), updated_at = :now
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
                  AND cancel_requested = FALSE
                """, Map.of("id", jobId, "workerId", workerId, "progress", progress,
                "now", JdbcTimes.toJdbc(now))) == 1;
    }

    @Override
    public boolean succeed(UUID jobId, String workerId, String resultReference, Instant now) {
        return jdbc.update("""
                UPDATE job
                SET status = 'SUCCEEDED', progress = 100, result_reference = :resultReference,
                    error_code = '', error_message = '', locked_by = NULL, lease_until = NULL, updated_at = :now
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
                  AND cancel_requested = FALSE
                """, Map.of("id", jobId, "workerId", workerId, "resultReference", resultReference,
                "now", JdbcTimes.toJdbc(now))) == 1;
    }

    @Override
    public boolean fail(UUID jobId, String workerId, String errorCode, String errorMessage, Instant now) {
        return jdbc.update("""
                UPDATE job
                SET status = 'FAILED', error_code = :errorCode, error_message = :errorMessage,
                    locked_by = NULL, lease_until = NULL, updated_at = :now
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
                """, Map.of("id", jobId, "workerId", workerId, "errorCode", errorCode,
                "errorMessage", errorMessage, "now", JdbcTimes.toJdbc(now))) == 1;
    }

    @Override
    public boolean cancellationRequested(UUID jobId) {
        Boolean value = jdbc.queryForObject("SELECT cancel_requested FROM job WHERE id = :id",
                Map.of("id", jobId), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    private static LeasedJob mapLeasedJob(ResultSet resultSet, int rowNumber) throws SQLException {
        Job job = JdbcJobRepository.mapJob(resultSet, rowNumber);
        return new LeasedJob(job, resultSet.getString("locked_by"),
                resultSet.getTimestamp("lease_until").toInstant(), resultSet.getInt("attempt"));
    }
}
