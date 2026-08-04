package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.api.stream.JobEventStream;
import com.knowledgemeltingpot.workbench.application.service.JobService;
import com.knowledgemeltingpot.workbench.application.service.JobSubmission;
import com.knowledgemeltingpot.workbench.application.service.ExtractionService;
import com.knowledgemeltingpot.workbench.domain.Job;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class JobController {
    private static final Pattern PUBLIC_ERROR_CODE = Pattern.compile("[A-Z0-9_:-]{1,100}");
    private final JobService jobService;
    private final ExtractionService extractionService;
    private final JobEventStream eventStream;
    private final CurrentUser currentUser;

    public JobController(JobService jobService, ExtractionService extractionService, JobEventStream eventStream,
            CurrentUser currentUser) {
        this.jobService = jobService;
        this.extractionService = extractionService;
        this.eventStream = eventStream;
        this.currentUser = currentUser;
    }

    @PostMapping("/subscenes/{subSceneId}/extraction-jobs")
    public ResponseEntity<JobAcceptedResponse> submitExtraction(@PathVariable UUID subSceneId,
            @Valid @RequestBody ExtractionJobRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        JobSubmission submission = extractionService.queue(subSceneId, body.roundId(), body.modelConfigVersionId(),
                body.skillVersionId(), currentUser.id(authentication), idempotencyKey,
                RequestIdFilter.currentTraceId());
        Job job = submission.job();
        URI status = URI.create("/api/v1/jobs/" + job.id());
        return ResponseEntity.accepted()
                .location(status)
                .header("X-Idempotent-Replay", Boolean.toString(submission.replayed()))
                .body(new JobAcceptedResponse(job.id(), status.toString(), status + "/events", job.status().name()));
    }

    @GetMapping("/jobs/{jobId}")
    public JobResponse get(@PathVariable UUID jobId, Authentication authentication) {
        Job job = jobService.get(jobId);
        requireOwnerOrAdmin(job, authentication);
        return JobResponse.from(job);
    }

    @GetMapping(value = "/jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID jobId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "after", required = false) Long after,
            Authentication authentication) {
        Job job = jobService.get(jobId);
        requireOwnerOrAdmin(job, authentication);
        return eventStream.open(jobId, after == null ? parseEventId(lastEventId) : after);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<JobAcceptedResponse> cancel(@PathVariable UUID jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Job job = jobService.get(jobId);
        requireOwnerOrAdmin(job, authentication);
        JobSubmission submission = jobService.cancel(jobId, currentUser.id(authentication), idempotencyKey,
                RequestIdFilter.currentTraceId());
        Job cancelled = submission.job();
        URI status = URI.create("/api/v1/jobs/" + jobId);
        return ResponseEntity.accepted().location(status)
                .header("X-Idempotent-Replay", Boolean.toString(submission.replayed()))
                .body(new JobAcceptedResponse(cancelled.id(), status.toString(), status + "/events",
                        cancelled.status().name()));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<JobAcceptedResponse> retry(@PathVariable UUID jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Job job = jobService.get(jobId);
        requireOwnerOrAdmin(job, authentication);
        JobSubmission submission = jobService.retry(jobId, currentUser.id(authentication), idempotencyKey,
                RequestIdFilter.currentTraceId());
        Job retried = submission.job();
        URI status = URI.create("/api/v1/jobs/" + jobId);
        return ResponseEntity.accepted().location(status)
                .header("X-Idempotent-Replay", Boolean.toString(submission.replayed()))
                .body(new JobAcceptedResponse(retried.id(), status.toString(), status + "/events",
                        retried.status().name()));
    }

    private long parseEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative event id");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative number");
        }
    }

    private void requireOwnerOrAdmin(Job job, Authentication authentication) {
        if (!job.requestedBy().equals(currentUser.id(authentication)) && !currentUser.isAdmin(authentication)) {
            throw new AccessDeniedException("job belongs to another user");
        }
    }

    public record ExtractionJobRequest(
            @NotNull UUID roundId,
            @NotNull UUID modelConfigVersionId,
            @NotNull UUID skillVersionId) {
    }

    public record JobAcceptedResponse(UUID jobId, String statusUrl, String eventsUrl, String status) {
    }

    public record JobResponse(
            UUID id,
            String type,
            String status,
            String stage,
            int percent,
            int attempt,
            String errorCode,
            Instant createdAt,
            Instant updatedAt) {

        static JobResponse from(Job job) {
            String publicErrorCode = PUBLIC_ERROR_CODE.matcher(job.errorCode()).matches()
                    ? job.errorCode()
                    : null;
            return new JobResponse(job.id(), job.type().name(), job.status().name(), job.status().name(),
                    job.progress(), job.attempt(), publicErrorCode, job.createdAt(), job.updatedAt());
        }
    }
}
