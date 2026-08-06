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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${workbench.agent.enabled:false}' == 'true' or "
        + "'${workbench.agent.test-stub-enabled:false}' == 'true'")
public class SceneExplorationJobHandler implements JobHandler {
    private static final System.Logger LOGGER = System.getLogger(SceneExplorationJobHandler.class.getName());
    private static final int MAX_CHUNKS = 96;
    private static final int MAX_CONTEXT_CHARS = 30_000;
    private final ExplorationRepository explorations;
    private final ChunkRepository chunks;
    private final SceneExplorationWorkflowPort workflow;
    private final ExplorationResultValidator resultValidator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SceneExplorationJobHandler(ExplorationRepository explorations, ChunkRepository chunks,
            SceneExplorationWorkflowPort workflow, ExplorationResultValidator resultValidator,
            ObjectMapper objectMapper, Clock clock) {
        this.explorations = explorations;
        this.chunks = chunks;
        this.workflow = workflow;
        this.resultValidator = resultValidator;
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
            var request = new SceneExplorationWorkflowPort.ExplorationRequest(sessionId,
                    session.modelConfigVersionId(), session.skillVersionId(), sources);
            var result = resultValidator.normalize(request, workflow.explore(request));
            List<ExplorationCandidate> candidates = toCandidates(sessionId, result, sources);
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
        } catch (ExplorationResultValidator.ValidationException exception) {
            return fail(sessionId, exception.code(), "Scene exploration result failed validation");
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            return fail(sessionId, "EXPLORATION_RESULT_INVALID", "Scene exploration result failed validation");
        }
    }

    private List<SceneExplorationWorkflowPort.ExplorationSource> sources(List<Material> materials)
            throws JsonProcessingException {
        Map<UUID, List<MaterialChunk>> byMaterial = chunks.findForMaterials(materials.stream().map(Material::id).toList());
        ExplorationContextSelector.Selection selection = ExplorationContextSelector.select(
                materials, byMaterial, MAX_CHUNKS, MAX_CONTEXT_CHARS);
        List<SceneExplorationWorkflowPort.ExplorationSource> result = new ArrayList<>();
        int sourceOrdinal = 1;
        for (ExplorationContextSelector.SelectedMaterial materialSelection : selection.materials()) {
            List<SceneExplorationWorkflowPort.ExplorationChunk> selected = new ArrayList<>();
            for (ExplorationContextSelector.SelectedChunk selectedChunk : materialSelection.chunks()) {
                MaterialChunk chunk = selectedChunk.chunk();
                selected.add(new SceneExplorationWorkflowPort.ExplorationChunk(chunk.sourceRefCode(),
                        objectMapper.writeValueAsString(chunk.locator()), selectedChunk.content()));
            }
            Material material = materialSelection.material();
            result.add(new SceneExplorationWorkflowPort.ExplorationSource(material.id(),
                    "MAT-%02d".formatted(sourceOrdinal++), material.fileName(), selected));
        }
        LOGGER.log(System.Logger.Level.INFO,
                "Scene exploration context prepared: materials={0}, chunks={1}, characters={2}",
                result.size(), selection.chunkCount(), selection.characterCount());
        return result;
    }

    private List<ExplorationCandidate> toCandidates(UUID sessionId,
            SceneExplorationWorkflowPort.ExplorationResult result,
            List<SceneExplorationWorkflowPort.ExplorationSource> sources) {
        Map<String, UUID> materialsByCode = sources.stream().collect(java.util.stream.Collectors.toMap(
                SceneExplorationWorkflowPort.ExplorationSource::sourceCode,
                SceneExplorationWorkflowPort.ExplorationSource::materialId));
        List<ExplorationCandidate> candidates = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (var draft : result.candidates()) {
            List<UUID> materialIds = draft.sourceCodes().stream().map(materialsByCode::get).toList();
            candidates.add(new ExplorationCandidate(UUID.randomUUID(), sessionId, draft.rank(), draft.sceneName(),
                    draft.sceneDescription(), draft.subSceneName(), draft.subSceneDescription(), draft.rationale(),
                    draft.valueLevel(), draft.estimatedRuleCount(), draft.estimatedFlowCount(), draft.tags(),
                    materialIds, now));
        }
        return candidates.stream().sorted(java.util.Comparator.comparingInt(ExplorationCandidate::rank)).toList();
    }

    private JobHandlingResult fail(UUID sessionId, String code, String message) {
        explorations.transition(sessionId, ExplorationStatus.ANALYZING, ExplorationStatus.FAILED, Instant.now(clock));
        return JobHandlingResult.failure(code, message);
    }

    private String publicCode(String code) {
        return code != null && code.matches("[A-Z0-9_:-]{1,100}") ? code : "SCENE_EXPLORATION_FAILED";
    }
}
