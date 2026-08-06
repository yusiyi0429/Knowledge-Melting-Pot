package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import com.knowledgemeltingpot.workbench.application.port.ExtractionRunRepository;
import com.knowledgemeltingpot.workbench.application.port.FrozenExtractionChunk;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort.KnowledgeDraft;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort.RuleDraft;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.application.service.KnowledgeIrValidator;
import com.knowledgemeltingpot.workbench.application.service.KnowledgeMarkdownCodec;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.ExtractionRun;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import com.knowledgemeltingpot.workbench.worker.JobHandler;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${workbench.agent.enabled:false}' == 'true' or "
        + "'${workbench.agent.test-stub-enabled:false}' == 'true'")
public class AgentExtractionJobHandler implements JobHandler {
    private final KnowledgeExtractionWorkflowPort workflow;
    private final ExtractionRunRepository runRepository;
    private final KnowledgeIrValidator validator;
    private final KnowledgeMarkdownCodec markdownCodec;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentExtractionJobHandler(KnowledgeExtractionWorkflowPort workflow,
            ExtractionRunRepository runRepository, KnowledgeIrValidator validator,
            KnowledgeMarkdownCodec markdownCodec, DocumentService documentService,
            ObjectMapper objectMapper, Clock clock) {
        this.workflow = workflow;
        this.runRepository = runRepository;
        this.validator = validator;
        this.markdownCodec = markdownCodec;
        this.documentService = documentService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.EXTRACT || type == JobType.REEXTRACT;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) {
        ExtractionRun run = runRepository.findByJobId(leasedJob.job().id()).orElse(null);
        if (run == null) {
            return JobHandlingResult.failure("EXTRACTION_SNAPSHOT_MISSING",
                    "The frozen extraction snapshot is unavailable");
        }
        List<FrozenExtractionChunk> chunks = runRepository.findChunks(run.id());
        if (chunks.isEmpty()) {
            return fail(run, "EXTRACTION_SNAPSHOT_EMPTY", "The frozen extraction snapshot has no chunks");
        }
        try {
            runRepository.updateStage(run.id(), ExtractionRun.Stage.MAPPING, Instant.now(clock));
            List<KnowledgeDraft> mapResults = map(run, chunks, context);
            if (context.cancellationRequested()) {
                return JobHandlingResult.failure("CANCELLED", "Cancellation was requested");
            }

            runRepository.updateStage(run.id(), ExtractionRun.Stage.REDUCING, Instant.now(clock));
            context.progress(72, "reduce-started");
            KnowledgeDraft reduced = workflow.reduce(new KnowledgeExtractionWorkflowPort.ReduceRequest(
                    run.id(), run.modelConfigVersionId(), run.skillVersionId(), mapResults));
            validateDraftSources(reduced, chunks.stream().map(chunk -> chunk.sourceRef().code()).collect(
                    java.util.stream.Collectors.toSet()));

            KnowledgeIr ir = assemble(run, reduced, chunks);
            String irJson = objectMapper.writeValueAsString(ir);
            runRepository.insertReduceResult(run.id(), ir, sha256(irJson), Instant.now(clock));
            runRepository.updateStage(run.id(), ExtractionRun.Stage.PERSISTING, Instant.now(clock));
            context.progress(88, "revision-persisting");

            String markdown = markdownCodec.render(ir);
            DocumentRevision saved = documentService.save(run.documentId(), run.subSceneId(), markdown,
                    "Map/Reduce 萃取任务 " + leasedJob.job().id(), false,
                    run.baseEtag() == null ? "*" : run.baseEtag(), leasedJob.job().requestedBy(),
                    "worker:" + leasedJob.job().id());
            runRepository.updateStage(run.id(), ExtractionRun.Stage.SUCCEEDED, Instant.now(clock));
            context.progress(98, "revision-created");
            return JobHandlingResult.success("knowledge-document:" + saved.documentId() + ":revision:"
                    + saved.id());
        } catch (AgentKnowledgeExtractionWorkflowAdapter.WorkflowGenerationException exception) {
            return fail(run, publicAgentCode(exception.code()), "Agent extraction did not complete");
        } catch (UnprocessableEntityException | IllegalArgumentException exception) {
            return fail(run, "KNOWLEDGE_IR_INVALID", "The generated KnowledgeIR failed validation");
        } catch (JsonProcessingException exception) {
            return fail(run, "KNOWLEDGE_IR_SERIALIZATION_FAILED", "KnowledgeIR could not be serialized");
        }
    }

    private List<KnowledgeDraft> map(ExtractionRun run, List<FrozenExtractionChunk> chunks,
            WorkerJobContext context) throws JsonProcessingException {
        List<KnowledgeDraft> results = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            if (context.cancellationRequested()) {
                break;
            }
            FrozenExtractionChunk frozen = chunks.get(index);
            String checkpoint = runRepository.findMapResult(run.id(), frozen.chunk().id()).orElse(null);
            KnowledgeDraft result;
            if (checkpoint == null) {
                result = workflow.map(new KnowledgeExtractionWorkflowPort.MapRequest(
                        run.id(), run.modelConfigVersionId(), run.skillVersionId(), frozen.sourceRef().code(),
                        locator(frozen.sourceRef()), frozen.chunk().content()));
                validateDraftSources(result, Set.of(frozen.sourceRef().code()));
                String resultJson = objectMapper.writeValueAsString(result);
                runRepository.insertMapResult(run.id(), frozen.chunk().id(), resultJson, sha256(resultJson),
                        Instant.now(clock));
            } else {
                result = objectMapper.readValue(checkpoint, KnowledgeDraft.class);
                validateDraftSources(result, Set.of(frozen.sourceRef().code()));
            }
            results.add(result);
            int percent = 8 + (int) (((index + 1) * 56.0) / chunks.size());
            context.progress(percent, "map-chunk-completed");
        }
        return results;
    }

    private KnowledgeIr assemble(ExtractionRun run, KnowledgeDraft draft, List<FrozenExtractionChunk> chunks) {
        KnowledgeIr.Metadata metadata = new KnowledgeIr.Metadata(run.documentId(), run.subSceneId(), run.roundId(),
                run.canonicalInputHash());
        List<KnowledgeIr.Rule> rules = draft.rules().stream()
                .map(rule -> new KnowledgeIr.Rule("R-0000000000000000", required(rule.title(), "rule title"),
                        required(rule.condition(), "rule condition"), required(rule.conclusion(), "rule conclusion"),
                        rule.priority(), rule.exceptions(), rule.sourceRefs()))
                .toList();
        List<KnowledgeIr.SourceRef> refs = chunks.stream().map(FrozenExtractionChunk::sourceRef)
                .sorted(Comparator.comparing(KnowledgeIr.SourceRef::code)).toList();
        KnowledgeIr ir = new KnowledgeIr(KnowledgeIr.SCHEMA_VERSION, metadata, rules, draft.flows(),
                draft.conflicts(), draft.gaps(), refs);
        return validator.validate(validator.normalizeGenerated(ir));
    }

    private void validateDraftSources(KnowledgeDraft draft, Set<String> allowed) {
        if (draft == null) {
            throw new IllegalArgumentException("knowledge draft is required");
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        draft.rules().forEach(rule -> refs.addAll(rule.sourceRefs()));
        draft.flows().forEach(flow -> flow.nodes().forEach(node -> refs.addAll(node.sourceRefs())));
        draft.conflicts().forEach(conflict -> refs.addAll(conflict.sourceRefs()));
        if (!allowed.containsAll(refs)) {
            throw new IllegalArgumentException("draft references a source outside the frozen snapshot");
        }
        for (RuleDraft rule : draft.rules()) {
            if (rule.sourceRefs().isEmpty()) {
                throw new IllegalArgumentException("every rule requires a source reference");
            }
        }
    }

    private String locator(KnowledgeIr.SourceRef ref) {
        return objectMapper.valueToTree(ref).toString();
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private JobHandlingResult fail(ExtractionRun run, String code, String message) {
        runRepository.updateStage(run.id(), ExtractionRun.Stage.FAILED, Instant.now(clock));
        return JobHandlingResult.failure(code, message);
    }

    private String publicAgentCode(String code) {
        return code != null && code.matches("[A-Z0-9_:-]{1,100}") ? code : "AGENT_EXTRACTION_FAILED";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
