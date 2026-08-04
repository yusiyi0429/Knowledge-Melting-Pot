package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.ContextBudget;
import com.knowledgemeltingpot.workbench.application.port.ExtractionRunRepository;
import com.knowledgemeltingpot.workbench.application.port.FrozenExtractionChunk;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.application.port.TrustedContext;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRun;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionService {
    private final SceneRepository sceneRepository;
    private final ModelConnectionRepository modelRepository;
    private final SkillRepository skillRepository;
    private final MaterialSelectionService materialSelectionService;
    private final DocumentService documentService;
    private final JobService jobService;
    private final ExtractionRunRepository runRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExtractionService(SceneRepository sceneRepository, ModelConnectionRepository modelRepository,
            SkillRepository skillRepository, MaterialSelectionService materialSelectionService,
            DocumentService documentService, JobService jobService, ExtractionRunRepository runRepository,
            AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.sceneRepository = sceneRepository;
        this.modelRepository = modelRepository;
        this.skillRepository = skillRepository;
        this.materialSelectionService = materialSelectionService;
        this.documentService = documentService;
        this.jobService = jobService;
        this.runRepository = runRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public JobSubmission queue(UUID subSceneId, UUID roundId, UUID modelConfigVersionId, UUID skillVersionId,
            UUID actorId, String idempotencyKey, String traceId) {
        sceneRepository.findSubScene(subSceneId)
                .orElseThrow(() -> new NotFoundException("sub-scene not found: " + subSceneId));
        ExtractionRound round = sceneRepository.findRound(roundId)
                .orElseThrow(() -> new NotFoundException("extraction round not found: " + roundId));
        if (!round.subSceneId().equals(subSceneId)) {
            throw new IllegalArgumentException("extraction round does not belong to the requested sub-scene");
        }
        modelRepository.findConfigVersion(modelConfigVersionId)
                .orElseThrow(() -> new NotFoundException("model configuration version not found: "
                        + modelConfigVersionId));
        skillRepository.findVersion(skillVersionId)
                .orElseThrow(() -> new NotFoundException("Skill version not found: " + skillVersionId));

        List<TrustedContext> contexts = materialSelectionService.knowledgeContext(roundId, subSceneId,
                ContextBudget.defaults());
        List<FrozenExtractionChunk> chunks = freeze(contexts);
        if (chunks.isEmpty()) {
            throw new ConflictException("no READY SOURCE/TRAIN chunks are available for extraction");
        }

        DocumentRevision base = documentService.find(subSceneId).orElse(null);
        String inputHash = canonicalInputHash(subSceneId, roundId, modelConfigVersionId, skillVersionId, base, chunks);
        Map<String, Object> payload = Map.of(
                "subSceneId", subSceneId,
                "roundId", roundId,
                "modelConfigVersionId", modelConfigVersionId,
                "skillVersionId", skillVersionId,
                "inputHash", inputHash);
        JobSubmission submission = jobService.submit(JobType.EXTRACT, "SUB_SCENE", subSceneId, payload,
                actorId, idempotencyKey, traceId);
        if (!submission.replayed()) {
            Instant now = Instant.now(clock);
            Job job = submission.job();
            ExtractionRun run = new ExtractionRun(UUID.randomUUID(), job.id(), subSceneId, subSceneId, roundId,
                    base == null ? null : base.id(), base == null ? null : base.etag(), modelConfigVersionId,
                    skillVersionId, inputHash, ExtractionRun.Stage.FROZEN, actorId, now, now);
            runRepository.insert(run, chunks);
            auditService.record(actorId, "EXTRACTION_RUN_FROZEN", "EXTRACTION_RUN", run.id(),
                    Map.of("jobId", job.id(), "documentId", subSceneId, "roundId", roundId,
                            "inputHash", inputHash, "chunkCount", chunks.size()), traceId);
        }
        return submission;
    }

    private List<FrozenExtractionChunk> freeze(List<TrustedContext> contexts) {
        Map<UUID, FrozenExtractionChunk> unique = new LinkedHashMap<>();
        contexts.stream()
                .sorted(Comparator.comparing(context -> context.selection().material().id().toString()))
                .forEach(context -> context.chunks().stream()
                        .sorted(Comparator.comparingInt(com.knowledgemeltingpot.workbench.domain.MaterialChunk::ordinal))
                        .forEach(chunk -> {
                            var material = context.selection().material();
                            var locator = chunk.locator();
                            KnowledgeIr.SourceRef ref = new KnowledgeIr.SourceRef(chunk.sourceRefCode(), material.id(),
                                    material.sha256(), chunk.id(), locator.type().name(), locator.page(),
                                    locator.paragraph(), locator.table(), locator.sheet(), locator.rowStart(),
                                    locator.rowEnd(), locator.colStart(), locator.colEnd(), locator.lineStart(),
                                    locator.lineEnd(), chunk.contentHash());
                            unique.putIfAbsent(chunk.id(), new FrozenExtractionChunk(material.id(), material.sha256(),
                                    context.selection().binding().partition(), chunk, ref));
                        }));
        return List.copyOf(unique.values());
    }

    private String canonicalInputHash(UUID subSceneId, UUID roundId, UUID modelVersionId, UUID skillVersionId,
            DocumentRevision base, List<FrozenExtractionChunk> chunks) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("subSceneId", subSceneId);
        canonical.put("roundId", roundId);
        canonical.put("modelConfigVersionId", modelVersionId);
        canonical.put("skillVersionId", skillVersionId);
        canonical.put("baseRevisionId", base == null ? null : base.id());
        canonical.put("chunks", chunks.stream()
                .map(chunk -> List.of(chunk.materialId(), chunk.materialSha256(), chunk.chunk().id(),
                        chunk.chunk().contentHash(), chunk.sourceRef().code()))
                .toList());
        try {
            return Hashes.sha256(objectMapper.writeValueAsString(canonical));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("extraction snapshot could not be hashed", exception);
        }
    }

}
