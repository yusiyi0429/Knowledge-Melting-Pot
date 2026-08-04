package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.ExplorationService;
import com.knowledgemeltingpot.workbench.application.service.JobSubmission;
import com.knowledgemeltingpot.workbench.domain.ExplorationCandidate;
import com.knowledgemeltingpot.workbench.domain.ExplorationSession;
import com.knowledgemeltingpot.workbench.domain.Material;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
@RequestMapping("/api/v1/explorations")
public class ExplorationController {
    private final ExplorationService explorations;
    private final CurrentUser currentUser;

    public ExplorationController(ExplorationService explorations, CurrentUser currentUser) {
        this.explorations = explorations;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateRequest body,
            Authentication authentication) {
        ExplorationSession created = explorations.create(body.title(), currentUser.id(authentication),
                RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/explorations/" + created.id()))
                .eTag(explorations.etag(created)).body(SessionResponse.from(created));
    }

    @GetMapping
    public List<SessionResponse> list() {
        return explorations.list().stream().map(SessionResponse::from).toList();
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<DetailResponse> get(@PathVariable UUID sessionId) {
        ExplorationService.ExplorationDetail detail = explorations.get(sessionId);
        return ResponseEntity.ok().eTag(detail.etag()).body(DetailResponse.from(detail));
    }

    @PostMapping("/{sessionId}/analysis-jobs")
    public ResponseEntity<JobAcceptedResponse> analyze(@PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        JobSubmission submission = explorations.start(sessionId, currentUser.id(authentication), idempotencyKey,
                RequestIdFilter.currentTraceId());
        URI status = URI.create("/api/v1/jobs/" + submission.job().id());
        return ResponseEntity.accepted().location(status)
                .header("X-Idempotent-Replay", Boolean.toString(submission.replayed()))
                .body(new JobAcceptedResponse(submission.job().id(), submission.job().status().name(),
                        status.toString(), status + "/events"));
    }

    @PostMapping("/{sessionId}/candidates/{candidateId}/accept")
    public AcceptanceResponse accept(@PathVariable UUID sessionId, @PathVariable UUID candidateId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody(required = false) AcceptanceRequest body, Authentication authentication) {
        ExplorationService.AcceptanceDraft draft = body == null ? null : new ExplorationService.AcceptanceDraft(
                body.sceneName(), body.sceneDescription(), body.subSceneName(), body.subSceneDescription());
        return AcceptanceResponse.from(explorations.accept(sessionId, candidateId, draft, ifMatch,
                currentUser.id(authentication), RequestIdFilter.currentTraceId()));
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String title) { }

    public record AcceptanceRequest(@Size(max = 200) String sceneName,
            @Size(max = 10_000) String sceneDescription,
            @Size(max = 200) String subSceneName,
            @Size(max = 10_000) String subSceneDescription) { }

    public record SessionResponse(UUID id, String title, String status, UUID exploreJobId,
            int version, Instant createdAt, Instant updatedAt) {
        static SessionResponse from(ExplorationSession value) {
            return new SessionResponse(value.id(), value.title(), value.status().name(), value.exploreJobId(),
                    value.version(), value.createdAt(), value.updatedAt());
        }
    }

    public record DetailResponse(SessionResponse session, String etag, List<MaterialResponse> materials,
            List<CandidateResponse> candidates, AcceptanceInfo acceptance) {
        static DetailResponse from(ExplorationService.ExplorationDetail detail) {
            var acceptance = detail.acceptance() == null ? null : new AcceptanceInfo(detail.acceptance().candidateId(),
                    detail.acceptance().sceneId(), detail.acceptance().subSceneId(), detail.acceptance().roundId(),
                    detail.acceptance().acceptedAt());
            return new DetailResponse(SessionResponse.from(detail.session()), detail.etag(),
                    detail.materials().stream().map(MaterialResponse::from).toList(),
                    detail.candidates().stream().map(CandidateResponse::from).toList(), acceptance);
        }
    }

    public record MaterialResponse(UUID id, String fileName, String format, long sizeBytes, String status,
            Instant createdAt, Instant updatedAt) {
        static MaterialResponse from(Material value) {
            return new MaterialResponse(value.id(), value.fileName(), value.format().name(), value.sizeBytes(),
                    value.status().name(), value.createdAt(), value.updatedAt());
        }
    }

    public record CandidateResponse(UUID id, int rank, String sceneName, String sceneDescription,
            String subSceneName, String subSceneDescription, String rationale, String valueLevel,
            int estimatedRuleCount, int estimatedFlowCount, List<String> tags, List<UUID> materialIds) {
        static CandidateResponse from(ExplorationCandidate value) {
            return new CandidateResponse(value.id(), value.rank(), value.sceneName(), value.sceneDescription(),
                    value.subSceneName(), value.subSceneDescription(), value.rationale(), value.valueLevel().name(),
                    value.estimatedRuleCount(), value.estimatedFlowCount(), value.tags(), value.materialIds());
        }
    }

    public record AcceptanceInfo(UUID candidateId, UUID sceneId, UUID subSceneId, UUID roundId,
            Instant acceptedAt) { }

    public record AcceptanceResponse(UUID sceneId, UUID subSceneId, UUID roundId,
            List<UUID> reusedMaterialIds) {
        static AcceptanceResponse from(ExplorationService.AcceptanceResult value) {
            return new AcceptanceResponse(value.scene().id(), value.subScene().id(), value.round().id(),
                    value.reusedMaterialIds());
        }
    }

    public record JobAcceptedResponse(UUID jobId, String status, String statusUrl, String eventsUrl) { }
}
