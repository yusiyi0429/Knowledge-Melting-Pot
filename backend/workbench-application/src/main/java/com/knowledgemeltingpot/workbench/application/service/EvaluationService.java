package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.EvaluationRepository;
import com.knowledgemeltingpot.workbench.application.port.ReleaseItemSnapshot;
import com.knowledgemeltingpot.workbench.application.port.ReleaseRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.EvaluationCase;
import com.knowledgemeltingpot.workbench.domain.EvaluationCaseResult;
import com.knowledgemeltingpot.workbench.domain.EvaluationRun;
import com.knowledgemeltingpot.workbench.domain.EvaluationStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.ReleaseStatus;
import com.knowledgemeltingpot.workbench.domain.ReleaseSubSceneStatus;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {
    private static final int MAX_RECENT = 30;
    private final EvaluationRepository evaluations;
    private final ReleaseRepository releases;
    private final SceneRepository scenes;
    private final MaterialSelectionService materials;
    private final JobService jobs;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvaluationService(EvaluationRepository evaluations, ReleaseRepository releases, SceneRepository scenes,
            MaterialSelectionService materials, JobService jobs, AuditService audit, ObjectMapper objectMapper,
            Clock clock) {
        this.evaluations = evaluations;
        this.releases = releases;
        this.scenes = scenes;
        this.materials = materials;
        this.jobs = jobs;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public EvaluationSubmission request(UUID releaseId, UUID subSceneId, UUID roundId, UUID actorId,
            String idempotencyKey, String traceId) {
        var release = releases.find(releaseId)
                .orElseThrow(() -> new NotFoundException("release does not exist"));
        if (release.status() != ReleaseStatus.PUBLISHED) {
            throw new ConflictException("only a published release can be evaluated");
        }
        var subScene = scenes.findSubScene(subSceneId)
                .orElseThrow(() -> new NotFoundException("sub-scene does not exist"));
        if (!subScene.sceneId().equals(release.sceneId())) {
            throw new IllegalArgumentException("sub-scene does not belong to the release scene");
        }
        scenes.findRound(roundId).filter(round -> round.subSceneId().equals(subSceneId))
                .orElseThrow(() -> new NotFoundException("round does not belong to sub-scene"));
        if (materials.forEvaluation(roundId, subSceneId).isEmpty()) {
            throw new ConflictException("at least one READY LABELED_HOLDOUT material is required");
        }

        List<ReleaseItemSnapshot> items = releases.findItems(releaseId).stream()
                .filter(item -> item.subSceneId().equals(subSceneId)).toList();
        ReleaseItemSnapshot evaluationAsset = requireItem(items, AssetType.EVALUATION_SET);
        ReleaseItemSnapshot skillAsset = requireItem(items, AssetType.SKILL_PACKAGE);
        if (evaluationAsset.documentRevisionId() == null
                || !evaluationAsset.documentRevisionId().equals(skillAsset.documentRevisionId())) {
            throw new ConflictException("released Skill and evaluation set do not share one document revision");
        }

        ReleaseManifest manifest = parseManifest(release.manifestJson());
        ReleaseManifest.SubSceneEntry entry = manifest.subScenes().stream()
                .filter(value -> value.subSceneId().equals(subSceneId)
                        && value.status() != ReleaseSubSceneStatus.MISSING)
                .findFirst().orElseThrow(() -> new ConflictException("sub-scene is not covered by this release"));
        ReleaseManifest.AgentConfigurationEntry configuration = entry.agentConfigurations().stream()
                .filter(value -> value.role() == AgentRole.QA_EVALUATOR && value.enabled())
                .findFirst().orElseThrow(() -> new ConflictException(
                        "the release has no enabled QA evaluator configuration"));
        if (configuration.model() == null || configuration.skill() == null) {
            throw new ConflictException("the released QA evaluator is not fully configured");
        }

        String normalizedKey = idempotencyKey == null ? "" : idempotencyKey.strip();
        UUID runId = normalizedKey.isBlank() ? UUID.randomUUID() : UUID.nameUUIDFromBytes(
                ("evaluation:" + actorId + ":" + normalizedKey).getBytes(StandardCharsets.UTF_8));
        JobSubmission submission = jobs.submit(JobType.EVALUATE, "EVALUATION_RUN", runId,
                Map.of("evaluationRunId", runId, "releaseId", releaseId,
                        "subSceneId", subSceneId, "roundId", roundId),
                actorId, idempotencyKey, traceId);
        if (submission.replayed()) {
            EvaluationRun replayed = evaluations.find(submission.job().aggregateId())
                    .orElseThrow(() -> new ConflictException("idempotent evaluation is not available"));
            return new EvaluationSubmission(replayed, submission);
        }
        Instant now = Instant.now(clock);
        EvaluationRun run = evaluations.insert(new EvaluationRun(runId, releaseId, subSceneId, roundId,
                evaluationAsset.documentRevisionId(), evaluationAsset.assetId(), skillAsset.assetId(),
                configuration.model().configVersionId(), configuration.skill().skillVersionId(),
                submission.job().id(), "", EvaluationStatus.QUEUED, 0, 0, 0, 0, null, "",
                actorId, now, null, null, now));
        audit.record(actorId, "EVALUATION_REQUESTED", "EVALUATION_RUN", run.id(), Map.of(
                "releaseId", releaseId, "subSceneId", subSceneId, "roundId", roundId,
                "modelConfigVersionId", run.modelConfigVersionId(), "skillVersionId", run.skillVersionId(),
                "jobId", run.jobId()), traceId);
        return new EvaluationSubmission(run, submission);
    }

    @Transactional(readOnly = true)
    public EvaluationDetail get(UUID runId) {
        EvaluationRun run = evaluations.find(runId)
                .orElseThrow(() -> new NotFoundException("evaluation run does not exist"));
        return new EvaluationDetail(run, evaluations.findCases(runId), evaluations.findResults(runId));
    }

    @Transactional(readOnly = true)
    public List<EvaluationRun> list(UUID releaseId, UUID subSceneId) {
        releases.find(releaseId).orElseThrow(() -> new NotFoundException("release does not exist"));
        scenes.findSubScene(subSceneId).orElseThrow(() -> new NotFoundException("sub-scene does not exist"));
        return evaluations.findRecent(releaseId, subSceneId, MAX_RECENT);
    }

    private ReleaseItemSnapshot requireItem(List<ReleaseItemSnapshot> items, AssetType type) {
        return items.stream().filter(item -> item.assetType() == type).findFirst()
                .orElseThrow(() -> new ConflictException("release is missing asset: " + type));
    }

    private ReleaseManifest parseManifest(String json) {
        try {
            return objectMapper.readValue(json, ReleaseManifest.class);
        } catch (JsonProcessingException exception) {
            throw new ConflictException("release manifest cannot be evaluated");
        }
    }

    public record EvaluationSubmission(EvaluationRun run, JobSubmission job) { }

    public record EvaluationDetail(EvaluationRun run, List<EvaluationCase> cases,
            List<EvaluationCaseResult> results) {
        public EvaluationDetail {
            cases = List.copyOf(cases);
            results = List.copyOf(results);
        }
    }
}
