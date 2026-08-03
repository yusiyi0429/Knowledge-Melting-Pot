package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.JobRepository;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobEvent;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJobRepository implements JobRepository {
    private final JdbcClient jdbc;

    public JdbcJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Job insert(Job job) {
        jdbc.sql("""
                INSERT INTO job (
                    id, job_type, aggregate_type, aggregate_id, status, progress, payload,
                    result_reference, error_code, error_message, requested_by, created_at, updated_at)
                VALUES (
                    :id, :jobType, :aggregateType, :aggregateId, :status, :progress, CAST(:payload AS jsonb),
                    :resultReference, :errorCode, :errorMessage, :requestedBy, :createdAt, :updatedAt)
                """)
                .param("id", job.id())
                .param("jobType", job.type().name())
                .param("aggregateType", job.aggregateType())
                .param("aggregateId", job.aggregateId())
                .param("status", job.status().name())
                .param("progress", job.progress())
                .param("payload", job.payloadJson())
                .param("resultReference", job.resultReference())
                .param("errorCode", job.errorCode())
                .param("errorMessage", job.errorMessage())
                .param("requestedBy", job.requestedBy())
                .param("createdAt", JdbcTimes.toJdbc(job.createdAt()))
                .param("updatedAt", JdbcTimes.toJdbc(job.updatedAt()))
                .update();
        return job;
    }

    @Override
    public Optional<Job> find(UUID id) {
        return jdbc.sql("""
                SELECT id, job_type, aggregate_type, aggregate_id, status, progress, attempt, payload,
                       result_reference, error_code, error_message, requested_by, created_at, updated_at
                FROM job WHERE id = :id
                """)
                .param("id", id)
                .query(JdbcJobRepository::mapJob)
                .optional();
    }

    @Override
    public long appendEvent(UUID jobId, String eventType, String payloadJson, Instant occurredAt) {
        return jdbc.sql("""
                INSERT INTO job_event (job_id, event_type, payload, occurred_at)
                VALUES (:jobId, :eventType, CAST(:payload AS jsonb), :occurredAt)
                RETURNING sequence
                """)
                .param("jobId", jobId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .param("occurredAt", JdbcTimes.toJdbc(occurredAt))
                .query(Long.class)
                .single();
    }

    @Override
    public List<JobEvent> findEventsAfter(UUID jobId, long afterSequence, int limit) {
        return jdbc.sql("""
                SELECT sequence, job_id, event_type, payload, occurred_at
                FROM job_event
                WHERE job_id = :jobId AND sequence > :afterSequence
                ORDER BY sequence
                LIMIT :limit
                """)
                .param("jobId", jobId)
                .param("afterSequence", afterSequence)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new JobEvent(
                        resultSet.getLong("sequence"),
                        resultSet.getObject("job_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload"),
                        resultSet.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    @Override
    public boolean requestCancellation(UUID jobId, Instant now) {
        return jdbc.sql("""
                UPDATE job
                SET cancel_requested = TRUE, status = 'CANCELLED', locked_by = NULL, lease_until = NULL,
                    updated_at = :now
                WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
                """)
                .param("id", jobId)
                .param("now", JdbcTimes.toJdbc(now))
                .update() == 1;
    }

    @Override
    public boolean retry(UUID jobId, Instant now) {
        return jdbc.sql("""
                UPDATE job
                SET status = 'QUEUED', progress = 0, cancel_requested = FALSE, locked_by = NULL,
                    lease_until = NULL, error_code = '', error_message = '', updated_at = :now
                WHERE id = :id AND status = 'FAILED'
                """)
                .param("id", jobId)
                .param("now", JdbcTimes.toJdbc(now))
                .update() == 1;
    }

    static Job mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Job(
                resultSet.getObject("id", UUID.class),
                JobType.valueOf(resultSet.getString("job_type")),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                JobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("progress"),
                resultSet.getInt("attempt"),
                resultSet.getString("payload"),
                resultSet.getString("result_reference"),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getObject("requested_by", UUID.class),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
