package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.ChunkRepository;
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.SceneExplorationWorkflowPort;
import com.knowledgemeltingpot.workbench.domain.ExplorationCandidate;
import com.knowledgemeltingpot.workbench.domain.ExplorationSession;
import com.knowledgemeltingpot.workbench.domain.ExplorationStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.worker.JobHandler;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${workbench.agent.enabled:false}' == 'true' or "
        + "'${workbench.agent.test-stub-enabled:false}' == 'true'")
public class SceneExplorationJobHandler implements JobHandler {
    private static final int MAX_CHUNKS = 24;
    private static final int MAX_CONTEXT_CHARS = 30_000;
    private final ExplorationRepository explorations;
    private final ChunkRepository chunks;
    private final SceneExplorationWorkflowPort workflow;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SceneExplorationJobHandler(ExplorationRepository explorations, ChunkRepository chunks,
            SceneExplorationWorkflowPort workflow, ObjectMapper objectMapper, Clock clock) {
        this.explorations = explorations;
        this.chunks = chunks;
        this.workflow = workflow;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.SCENE_EXPLORE;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) {
        UUID sessionId = leasedJob.job().aggregateId();
        ExplorationSession session = explorations.find(sessionId).orElse(null);
        if (session == null || !leasedJob.job().id().equals(session.exploreJobId())) {
            return JobHandlingResult.failure("EXPLORATION_SNAPSHOT_MISSING", "Exploration snapshot is unavailable");
        }
        if (session.status() == ExplorationStatus.READY || session.status() == ExplorationStatus.ACCEPTED) {
            return JobHandlingResult.success("exploration:" + sessionId);
        }
        if (session.status() == ExplorationStatus.FAILED) {
            explorations.transition(sessionId, ExplorationStatus.FAILED, ExplorationStatus.ANALYZING,
                    Instant.now(clock));
            session = explorations.find(sessionId).orElse(session);
        }
        if (session.status() != ExplorationStatus.ANALYZING || session.modelConfigVersionId() == null
                || session.skillVersionId() == null) {
            return JobHandlingResult.failure("EXPLORATION_STATE_INVALID", "Exploration is not ready to analyze");
        }
        try {
            List<Material> materials = explorations.findMaterials(sessionId);
            if (materials.isEmpty() || materials.stream().anyMatch(value -> value.status() != MaterialStatus.READY)) {
                return fail(sessionId, "EXPLORATION_MATERIALS_NOT_READY", "Staged materials are not ready");
            }
            context.progress(15, "exploration-context-frozen");
            List<SceneExplorationWorkflowPort.ExplorationSource> sources = sources(materials);
            if (sources.isEmpty()) {
                return fail(sessionId, "EXPLORATION_CONTEXT_EMPTY", "Staged materials contain no parsed chunks");
            }
            context.progress(40, "exploration-agent-running");
            var result = workflow.explore(new SceneExplorationWorkflowPort.ExplorationRequest(sessionId,
                    session.modelConfigVersionId(), session.skillVersionId(), sources));
            List<ExplorationCandidate> candidates = validate(sessionId, result, materials);
            context.progress(85, "exploration-candidates-validating");
            if (!explorations.completeAnalysis(sessionId, candidates, Instant.now(clock))) {
                ExplorationSession current = explorations.find(sessionId).orElse(session);
                if (current.status() != ExplorationStatus.READY) {
                    return JobHandlingResult.failure("EXPLORATION_STATE_RACE", "Exploration state changed");
                }
            }
            context.progress(98, "exploration-candidates-persisted");
            return JobHandlingResult.success("exploration:" + sessionId);
        } catch (AgentKnowledgeExtractionWorkflowAdapter.WorkflowGenerationException exception) {
            return fail(sessionId, publicCode(exception.code()), "Scene exploration did not complete");
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            return fail(sessionId, "EXPLORATION_RESULT_INVALID", "Scene exploration result failed validation");
        }
    }

    private List<SceneExplorationWorkflowPort.ExplorationSource> sources(List<Material> materials)
            throws JsonProcessingException {
        Map<UUID, List<MaterialChunk>> byMaterial = chunks.findForMaterials(materials.stream().map(Material::id).toList());
        List<SceneExplorationWorkflowPort.ExplorationSource> result = new ArrayList<>();
        int remainingChars = MAX_CONTEXT_CHARS;
        int remainingChunks = MAX_CHUNKS;
        for (Material material : materials) {
            List<SceneExplorationWorkflowPort.ExplorationChunk> selected = new ArrayList<>();
            for (MaterialChunk chunk : byMaterial.getOrDefault(material.id(), List.of())) {
                if (remainingChunks == 0 || remainingChars == 0) break;
                String content = chunk.content().length() <= remainingChars
                        ? chunk.content() : chunk.content().substring(0, remainingChars);
                if (content.isBlank()) continue;
                selected.add(new SceneExplorationWorkflowPort.ExplorationChunk(chunk.sourceRefCode(),
                        objectMapper.writeValueAsString(chunk.locator()), content));
                remainingChars -= content.length();
                remainingChunks--;
            }
            if (!selected.isEmpty()) {
                result.add(new SceneExplorationWorkflowPort.ExplorationSource(material.id(), material.fileName(), selected));
            }
            if (remainingChunks == 0 || remainingChars == 0) break;
        }
        return result;
    }

    private List<ExplorationCandidate> validate(UUID sessionId, SceneExplorationWorkflowPort.ExplorationResult result,
            List<Material> materials) {
        if (result == null || result.candidates().isEmpty() || result.candidates().size() > 5) {
            throw new IllegalArgumentException("between one and five candidates are required");
        }
        Set<UUID> allowedMaterials = new HashSet<>(materials.stream().map(Material::id).toList());
        Set<Integer> ranks = new HashSet<>();
        List<ExplorationCandidate> candidates = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (var draft : result.candidates()) {
            if (!ranks.add(draft.rank()) || draft.rank() < 1 || draft.rank() > result.candidates().size()) {
                throw new IllegalArgumentException("candidate ranks must be unique and contiguous");
            }
            if (draft.materialIds().isEmpty() || !allowedMaterials.containsAll(draft.materialIds())
                    || new HashSet<>(draft.materialIds()).size() != draft.materialIds().size()) {
                throw new IllegalArgumentException("candidate material references are invalid");
            }
            if (draft.tags().size() > 8 || draft.tags().stream().anyMatch(tag -> tag == null || tag.isBlank()
                    || tag.length() > 40)) {
                throw new IllegalArgumentException("candidate tags are invalid");
            }
            require(draft.sceneName(), 200); require(draft.subSceneName(), 200); require(draft.rationale(), 2_000);
            candidates.add(new ExplorationCandidate(UUID.randomUUID(), sessionId, draft.rank(), draft.sceneName(),
                    bounded(draft.sceneDescription(), 10_000), draft.subSceneName(),
                    bounded(draft.subSceneDescription(), 10_000), draft.rationale(), draft.valueLevel(),
                    draft.estimatedRuleCount(), draft.estimatedFlowCount(), draft.tags(), draft.materialIds(), now));
        }
        return candidates.stream().sorted(java.util.Comparator.comparingInt(ExplorationCandidate::rank)).toList();
    }

    private void require(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("text invalid");
    }

    private String bounded(String value, int max) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > max) throw new IllegalArgumentException("text too long");
        return normalized;
    }

    private JobHandlingResult fail(UUID sessionId, String code, String message) {
        explorations.transition(sessionId, ExplorationStatus.ANALYZING, ExplorationStatus.FAILED, Instant.now(clock));
        return JobHandlingResult.failure(code, message);
    }

    private String publicCode(String code) {
        return code != null && code.matches("[A-Z0-9_:-]{1,100}") ? code : "SCENE_EXPLORATION_FAILED";
    }
}
