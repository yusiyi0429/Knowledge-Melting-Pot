package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.EvaluationService;
import com.knowledgemeltingpot.workbench.domain.EvaluationCase;
import com.knowledgemeltingpot.workbench.domain.EvaluationCaseResult;
import com.knowledgemeltingpot.workbench.domain.EvaluationRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class EvaluationController {
    private final EvaluationService evaluations;
    private final CurrentUser currentUser;

    public EvaluationController(EvaluationService evaluations, CurrentUser currentUser) {
        this.evaluations = evaluations;
        this.currentUser = currentUser;
    }

    @PostMapping("/releases/{releaseId}/subscenes/{subSceneId}/evaluation-jobs")
    public ResponseEntity<EvaluationAcceptedResponse> create(@PathVariable UUID releaseId,
            @PathVariable UUID subSceneId, @Valid @RequestBody CreateEvaluationRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        var submission = evaluations.request(releaseId, subSceneId, body.roundId(),
                currentUser.id(authentication), idempotencyKey, RequestIdFilter.currentTraceId());
        URI status = URI.create("/api/v1/jobs/" + submission.job().job().id());
        return ResponseEntity.accepted().location(status)
                .header("X-Idempotent-Replay", Boolean.toString(submission.job().replayed()))
                .body(new EvaluationAcceptedResponse(submission.run().id(), submission.job().job().id(),
                        submission.job().job().status().name(), status.toString(), status + "/events"));
    }

    @GetMapping("/releases/{releaseId}/subscenes/{subSceneId}/evaluation-runs")
    public List<EvaluationRunResponse> list(@PathVariable UUID releaseId, @PathVariable UUID subSceneId) {
        return evaluations.list(releaseId, subSceneId).stream().map(EvaluationRunResponse::from).toList();
    }

    @GetMapping("/evaluation-runs/{runId}")
    public EvaluationDetailResponse get(@PathVariable UUID runId) {
        var detail = evaluations.get(runId);
        Map<UUID, EvaluationCaseResult> results = detail.results().stream()
                .collect(Collectors.toMap(EvaluationCaseResult::caseId, Function.identity()));
        return new EvaluationDetailResponse(EvaluationRunResponse.from(detail.run()),
                detail.cases().stream().map(value -> EvaluationCaseResponse.from(value, results.get(value.id())))
                        .toList());
    }

    public record CreateEvaluationRequest(@NotNull UUID roundId) { }

    public record EvaluationAcceptedResponse(UUID evaluationRunId, UUID jobId, String status,
            String statusUrl, String eventsUrl) { }

    public record EvaluationRunResponse(UUID id, UUID releaseId, UUID subSceneId, UUID roundId,
            UUID documentRevisionId, UUID evaluationAssetId, UUID skillAssetId,
            UUID modelConfigVersionId, UUID skillVersionId, UUID jobId, String caseSetHash,
            String status, int totalCases, int passedCases, int failedCases, int errorCases,
            BigDecimal accuracy, String failureCode, Instant createdAt, Instant startedAt,
            Instant completedAt, Instant updatedAt) {
        static EvaluationRunResponse from(EvaluationRun value) {
            return new EvaluationRunResponse(value.id(), value.releaseId(), value.subSceneId(), value.roundId(),
                    value.documentRevisionId(), value.evaluationAssetId(), value.skillAssetId(),
                    value.modelConfigVersionId(), value.skillVersionId(), value.jobId(), value.caseSetHash(),
                    value.status().name(), value.totalCases(), value.passedCases(), value.failedCases(),
                    value.errorCases(), value.accuracy(), value.failureCode(), value.createdAt(), value.startedAt(),
                    value.completedAt(), value.updatedAt());
        }
    }

    public record EvaluationDetailResponse(EvaluationRunResponse run, List<EvaluationCaseResponse> cases) { }

    public record EvaluationCaseResponse(UUID id, int ordinal, String caseKey, String input, String expected,
            UUID materialId, UUID chunkId, String sourceRefCode, List<String> tags,
            String prediction, String outcome, String errorCode, Long latencyMillis) {
        static EvaluationCaseResponse from(EvaluationCase value, EvaluationCaseResult result) {
            return new EvaluationCaseResponse(value.id(), value.ordinal(), value.caseKey(), value.input(),
                    value.expected(), value.materialId(), value.chunkId(), value.sourceRefCode(), value.tags(),
                    result == null ? null : result.prediction(), result == null ? null : result.outcome().name(),
                    result == null ? null : result.errorCode(), result == null ? null : result.latencyMillis());
        }
    }
}
