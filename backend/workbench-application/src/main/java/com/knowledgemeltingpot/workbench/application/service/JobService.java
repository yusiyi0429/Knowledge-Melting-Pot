package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRecord;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import com.knowledgemeltingpot.workbench.application.port.JobRepository;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobEvent;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 8;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    private final JobRepository jobRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JobService(JobRepository jobRepository, IdempotencyRepository idempotencyRepository,
            AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.jobRepository = jobRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public JobSubmission submit(JobType type, String aggregateType, UUID aggregateId, Map<String, ?> payload,
            UUID actorId, String idempotencyKey, String traceId) {
        String payloadJson = toJson(payload);
        String requestHash = Hashes.sha256(type + "\n" + aggregateType + "\n" + aggregateId + "\n" + payloadJson);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String scope = actorId.toString();

        if (!normalizedKey.isBlank()) {
            JobSubmission replay = replay(scope, normalizedKey, requestHash);
            if (replay != null) {
                return replay;
            }
        }

        Instant now = Instant.now(clock);
        UUID jobId = UUID.randomUUID();
        if (!normalizedKey.isBlank()) {
            IdempotencyRecord reservation = new IdempotencyRecord(scope, normalizedKey, requestHash, "JOB", jobId,
                    now, now.plus(IDEMPOTENCY_TTL));
            if (!idempotencyRepository.tryReserve(reservation)) {
                JobSubmission replay = replay(scope, normalizedKey, requestHash);
                if (replay != null) {
                    return replay;
                }
                throw new ConflictException("idempotency key is already being processed");
            }
        }

        Job job = jobRepository.insert(new Job(jobId, type, aggregateType, aggregateId, JobStatus.QUEUED, 0,
                0, payloadJson, "", "", "", actorId, now, now));
        jobRepository.appendEvent(job.id(), "queued", toJson(Map.of(
                "status", job.status(),
                "stage", "QUEUED",
                "percent", 0,
                "messageCode", "JOB_QUEUED",
                "traceId", publicTraceId(traceId, job.id()))), now);
        auditService.record(actorId, "JOB_SUBMITTED", "JOB", job.id(),
                Map.of("type", type, "aggregateId", aggregateId), traceId);
        return new JobSubmission(job, false);
    }

    @Transactional(readOnly = true)
    public Job get(UUID jobId) {
        return jobRepository.find(jobId)
                .orElseThrow(() -> new NotFoundException("job not found: " + jobId));
    }

    @Transactional(readOnly = true)
    public List<JobEvent> eventsAfter(UUID jobId, long afterSequence, int limit) {
        get(jobId);
        return jobRepository.findEventsAfter(jobId, Math.max(0, afterSequence), Math.min(Math.max(limit, 1), 500));
    }

    @Transactional
    public JobSubmission cancel(UUID jobId, UUID actorId, String idempotencyKey, String traceId) {
        JobSubmission replay = reserveCommand(jobId, actorId, idempotencyKey, "CANCEL");
        if (replay != null) {
            return replay;
        }
        Job job = get(jobId);
        if (job.status().terminal()) {
            throw new ConflictException("terminal job cannot be canceled");
        }
        Instant now = Instant.now(clock);
        if (!jobRepository.requestCancellation(jobId, now)) {
            throw new ConflictException("job cannot be canceled in its current state");
        }
        jobRepository.appendEvent(jobId, "cancelled", toJson(Map.of(
                "status", JobStatus.CANCELLED,
                "stage", "CANCELLED",
                "percent", job.progress(),
                "messageCode", "JOB_CANCELLED",
                "traceId", publicTraceId(traceId, jobId))), now);
        auditService.record(actorId, "JOB_CANCELLED", "JOB", jobId, Map.of(), traceId);
        return new JobSubmission(get(jobId), false);
    }

    @Transactional
    public JobSubmission retry(UUID jobId, UUID actorId, String idempotencyKey, String traceId) {
        JobSubmission replay = reserveCommand(jobId, actorId, idempotencyKey, "RETRY");
        if (replay != null) {
            return replay;
        }
        Job job = get(jobId);
        if (job.status() != JobStatus.FAILED || !jobRepository.retry(jobId, Instant.now(clock))) {
            throw new ConflictException("only a failed job can be retried");
        }
        Instant now = Instant.now(clock);
        jobRepository.appendEvent(jobId, "queued", toJson(Map.of(
                "status", JobStatus.QUEUED,
                "stage", "QUEUED",
                "percent", 0,
                "retry", true,
                "messageCode", "JOB_RETRY_QUEUED",
                "traceId", publicTraceId(traceId, jobId))), now);
        auditService.record(actorId, "JOB_RETRIED", "JOB", jobId, Map.of(), traceId);
        return new JobSubmission(get(jobId), false);
    }

    public long appendEvent(UUID jobId, String type, Map<String, ?> payload) {
        return jobRepository.appendEvent(jobId, type, toJson(payload), Instant.now(clock));
    }

    private JobSubmission replay(String scope, String key, String requestHash) {
        return idempotencyRepository.find(scope, key).map(record -> {
            if (!record.requestHash().equals(requestHash)) {
                throw new ConflictException("idempotency key was used with a different request");
            }
            Job job = jobRepository.find(record.resourceId())
                    .orElseThrow(() -> new ConflictException("idempotent resource is not available"));
            return new JobSubmission(job, true);
        }).orElse(null);
    }

    private JobSubmission reserveCommand(UUID jobId, UUID actorId, String idempotencyKey, String command) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey.isBlank()) {
            return null;
        }
        String scope = actorId.toString();
        String requestHash = Hashes.sha256(command + "\n" + jobId);
        JobSubmission existing = replay(scope, normalizedKey, requestHash);
        if (existing != null) {
            return existing;
        }
        Instant now = Instant.now(clock);
        IdempotencyRecord reservation = new IdempotencyRecord(scope, normalizedKey, requestHash,
                "JOB_COMMAND", jobId, now, now.plus(IDEMPOTENCY_TTL));
        if (!idempotencyRepository.tryReserve(reservation)) {
            existing = replay(scope, normalizedKey, requestHash);
            if (existing != null) {
                return existing;
            }
            throw new ConflictException("idempotency key is already being processed");
        }
        return null;
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() < IDEMPOTENCY_KEY_MIN_LENGTH
                || normalized.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must contain between 8 and 128 characters");
        }
        return normalized;
    }

    private String publicTraceId(String value, UUID jobId) {
        return value == null || value.isBlank() ? "job-" + jobId : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payload is not serializable", exception);
        }
    }
}
