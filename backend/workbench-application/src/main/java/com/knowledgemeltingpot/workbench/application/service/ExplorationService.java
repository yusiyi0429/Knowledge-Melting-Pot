package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionRequiredException;
import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.ExplorationCandidate;
import com.knowledgemeltingpot.workbench.domain.ExplorationSession;
import com.knowledgemeltingpot.workbench.domain.ExplorationStatus;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExplorationService {
    private static final int MAX_RECENT = 50;
    private final ExplorationRepository explorations;
    private final AgentConfigurationService agentConfigurations;
    private final JobService jobs;
    private final SceneRepository scenes;
    private final MaterialRepository materials;
    private final AssetRepository assets;
    private final AuditService audit;
    private final Clock clock;

    public ExplorationService(ExplorationRepository explorations, AgentConfigurationService agentConfigurations,
            JobService jobs, SceneRepository scenes, MaterialRepository materials, AssetRepository assets,
            AuditService audit, Clock clock) {
        this.explorations = explorations;
        this.agentConfigurations = agentConfigurations;
        this.jobs = jobs;
        this.scenes = scenes;
        this.materials = materials;
        this.assets = assets;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ExplorationSession create(String title, UUID actorId, String traceId) {
        Instant now = Instant.now(clock);
        String normalized = title == null ? "" : title.strip();
        if (normalized.isBlank() || normalized.length() > 200) {
            throw new IllegalArgumentException("exploration title must contain between 1 and 200 characters");
        }
        ExplorationSession session = explorations.insert(new ExplorationSession(UUID.randomUUID(), normalized,
                ExplorationStatus.DRAFT, null, null, null, null, "", 0, actorId, now, now));
        audit.record(actorId, "EXPLORATION_CREATED", "EXPLORATION", session.id(), Map.of("title", normalized), traceId);
        return session;
    }

    @Transactional(readOnly = true)
    public ExplorationDetail get(UUID sessionId) {
        ExplorationSession session = require(sessionId);
        return new ExplorationDetail(session, etag(session), explorations.findMaterials(sessionId),
                explorations.findCandidates(sessionId), explorations.findAcceptance(sessionId).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<ExplorationSession> list() {
        return explorations.findRecent(MAX_RECENT);
    }

    @Transactional
    public JobSubmission start(UUID sessionId, UUID actorId, String idempotencyKey, String traceId) {
        ExplorationSession session = explorations.lock(sessionId)
                .orElseThrow(() -> new NotFoundException("exploration session does not exist"));
        if (session.status() == ExplorationStatus.ANALYZING && session.exploreJobId() != null) {
            return new JobSubmission(jobs.get(session.exploreJobId()), true);
        }
        if (session.status() != ExplorationStatus.DRAFT) {
            throw new ConflictException("only a draft exploration can be analyzed");
        }
        List<Material> staged = explorations.findMaterials(sessionId);
        if (staged.isEmpty()) throw new ConflictException("at least one staged material is required");
        if (staged.stream().anyMatch(material -> material.status() != MaterialStatus.READY)) {
            throw new ConflictException("all staged materials must finish ingestion before analysis");
        }
        AgentConfigurationService.EffectiveAgentConfiguration configuration =
                agentConfigurations.resolveGlobal(AgentRole.SCENE_EXPLORER);
        if (!configuration.configured()) {
            throw new ConflictException("the global scene explorer Agent is not fully configured");
        }
        JobSubmission submission = jobs.submit(JobType.SCENE_EXPLORE, "EXPLORATION", sessionId, Map.of(
                "sessionId", sessionId,
                "materialIds", staged.stream().map(Material::id).toList(),
                "modelConfigVersionId", configuration.modelConfigVersionId(),
                "skillVersionId", configuration.skillVersionId(),
                "roleConfigVersionId", configuration.effectiveMountVersionId() == null
                        ? "" : configuration.effectiveMountVersionId().toString(),
                "effectiveConfigHash", configuration.effectiveHash()), actorId, idempotencyKey, traceId);
        if (!explorations.freezeRun(sessionId, session.version(), submission.job().id(),
                configuration.modelConfigVersionId(), configuration.skillVersionId(),
                configuration.effectiveMountVersionId(), configuration.effectiveHash(), Instant.now(clock))) {
            throw new ConflictException("exploration changed while analysis was starting");
        }
        audit.record(actorId, "EXPLORATION_STARTED", "EXPLORATION", sessionId,
                Map.of("jobId", submission.job().id(), "configHash", configuration.effectiveHash()), traceId);
        return submission;
    }

    @Transactional
    public AcceptanceResult accept(UUID sessionId, UUID candidateId, AcceptanceDraft draft, String ifMatch,
            UUID actorId, String traceId) {
        requireIfMatch(ifMatch);
        ExplorationSession session = explorations.lock(sessionId)
                .orElseThrow(() -> new NotFoundException("exploration session does not exist"));
        if (!etag(session).equals(normalizeEtag(ifMatch))) {
            throw new PreconditionFailedException("exploration changed; refresh before accepting a candidate");
        }
        if (session.status() != ExplorationStatus.READY) {
            throw new ConflictException("only a ready exploration candidate can be accepted");
        }
        ExplorationCandidate candidate = explorations.findCandidate(sessionId, candidateId)
                .orElseThrow(() -> new NotFoundException("exploration candidate does not exist"));
        List<Material> staged = explorations.findMaterials(sessionId);
        List<UUID> selectedIds = candidate.materialIds().isEmpty()
                ? staged.stream().filter(value -> value.status() == MaterialStatus.READY).map(Material::id).toList()
                : candidate.materialIds();
        if (selectedIds.isEmpty() || !staged.stream().map(Material::id).toList().containsAll(selectedIds)) {
            throw new ConflictException("candidate material references are invalid");
        }
        Instant now = Instant.now(clock);
        String sceneName = preferred(draft == null ? null : draft.sceneName(), candidate.sceneName(), 200, "sceneName");
        String sceneDescription = optional(draft == null ? null : draft.sceneDescription(), candidate.sceneDescription(), 10_000);
        String subSceneName = preferred(draft == null ? null : draft.subSceneName(), candidate.subSceneName(), 200, "subSceneName");
        String subSceneDescription = optional(draft == null ? null : draft.subSceneDescription(),
                candidate.subSceneDescription(), 10_000);
        Scene scene = scenes.save(new Scene(UUID.randomUUID(), sceneName, sceneDescription, now, now));
        SubScene subScene = scenes.save(new SubScene(UUID.randomUUID(), scene.id(), subSceneName,
                subSceneDescription, now, now));
        assets.ensurePlaceholders(subScene.id(), now);
        ExtractionRound round = scenes.createNextRound(subScene.id(), UUID.randomUUID(), now);
        List<RoundMaterial> bindings = selectedIds.stream().map(materialId -> new RoundMaterial(UUID.randomUUID(),
                materialId, round.id(), subScene.id(), MaterialPartition.SOURCE, MaterialShareScope.ROUND,
                false, true, now)).toList();
        materials.insertBindings(bindings);
        if (!explorations.accept(sessionId, session.version(), candidateId, scene.id(), subScene.id(), round.id(),
                actorId, now)) {
            throw new ConflictException("exploration candidate was accepted concurrently");
        }
        audit.record(actorId, "EXPLORATION_CANDIDATE_ACCEPTED", "EXPLORATION", sessionId, Map.of(
                "candidateId", candidateId, "sceneId", scene.id(), "subSceneId", subScene.id(),
                "roundId", round.id(), "reusedMaterialCount", bindings.size()), traceId);
        return new AcceptanceResult(scene, subScene, round, selectedIds);
    }

    public String etag(ExplorationSession session) {
        return Hashes.sha256(session.id() + "\n" + session.version() + "\n" + session.status()
                + "\n" + session.updatedAt());
    }

    private ExplorationSession require(UUID id) {
        return explorations.find(id).orElseThrow(() -> new NotFoundException("exploration session does not exist"));
    }

    private void requireIfMatch(String value) {
        if (value == null || value.isBlank()) throw new PreconditionRequiredException("If-Match is required");
    }

    private String normalizeEtag(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("W/")) normalized = normalized.substring(2).trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private String preferred(String requested, String fallback, int max, String field) {
        String value = requested == null || requested.isBlank() ? fallback : requested.strip();
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private String optional(String requested, String fallback, int max) {
        String value = requested == null ? fallback : requested.strip();
        value = value == null ? "" : value;
        if (value.length() > max) throw new IllegalArgumentException("description is too long");
        return value;
    }

    public record ExplorationDetail(ExplorationSession session, String etag, List<Material> materials,
            List<ExplorationCandidate> candidates, ExplorationRepository.ExplorationAcceptance acceptance) {
        public ExplorationDetail {
            materials = List.copyOf(materials);
            candidates = List.copyOf(candidates);
        }
    }

    public record AcceptanceDraft(String sceneName, String sceneDescription,
            String subSceneName, String subSceneDescription) { }

    public record AcceptanceResult(Scene scene, SubScene subScene, ExtractionRound round,
            List<UUID> reusedMaterialIds) {
        public AcceptanceResult { reusedMaterialIds = List.copyOf(reusedMaterialIds); }
    }
}
