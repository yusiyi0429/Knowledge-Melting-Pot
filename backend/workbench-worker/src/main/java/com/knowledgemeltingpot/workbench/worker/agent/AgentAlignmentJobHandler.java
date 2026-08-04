package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.ContextBudget;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeAlignmentWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeAlignmentWorkflowPort.Evidence;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.TrustedContext;
import com.knowledgemeltingpot.workbench.application.service.AlignmentProposalDraft;
import com.knowledgemeltingpot.workbench.application.service.AlignmentProposalView;
import com.knowledgemeltingpot.workbench.application.service.AlignmentService;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.application.service.KnowledgeIrValidator;
import com.knowledgemeltingpot.workbench.application.service.MaterialSelectionService;
import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import com.knowledgemeltingpot.workbench.worker.JobHandler;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${workbench.agent.enabled:false}' == 'true' or "
        + "'${workbench.agent.test-stub-enabled:false}' == 'true'")
public class AgentAlignmentJobHandler implements JobHandler {
    private final KnowledgeAlignmentWorkflowPort workflow;
    private final AlignmentService alignmentService;
    private final DocumentService documentService;
    private final MaterialSelectionService materialSelectionService;
    private final KnowledgeIrValidator validator;
    private final ObjectMapper objectMapper;

    public AgentAlignmentJobHandler(KnowledgeAlignmentWorkflowPort workflow, AlignmentService alignmentService,
            DocumentService documentService, MaterialSelectionService materialSelectionService,
            KnowledgeIrValidator validator, ObjectMapper objectMapper) {
        this.workflow = workflow;
        this.alignmentService = alignmentService;
        this.documentService = documentService;
        this.materialSelectionService = materialSelectionService;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.ALIGN;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) {
        try {
            Payload payload = objectMapper.readValue(leasedJob.job().payloadJson(), Payload.class);
            KnowledgeIr base = documentService.getProjection(payload.baseRevisionId());
            context.progress(15, "alignment-base-loaded");
            List<Evidence> evidence = evidence(payload, base);
            context.progress(35, "alignment-evidence-frozen");
            if (context.cancellationRequested()) {
                return JobHandlingResult.failure("CANCELLED", "Cancellation was requested");
            }
            var generated = workflow.generate(new KnowledgeAlignmentWorkflowPort.AlignmentRequest(
                    leasedJob.job().id(), payload.modelConfigVersionId(), payload.skillVersionId(),
                    payload.action(), base, evidence));
            KnowledgeIr replacement = validator.validate(validator.assignStableRuleIds(generated.replacement()));
            context.progress(80, "alignment-proposal-validating");
            AlignmentProposalView proposal = alignmentService.createProposal(new AlignmentProposalDraft(
                    payload.documentId(), payload.baseRevisionId(), payload.action(), replacement,
                    generated.reason(), payload.regulatoryMaterialIds()), leasedJob.job().requestedBy(),
                    "worker:" + leasedJob.job().id());
            context.progress(98, "alignment-proposal-created");
            return JobHandlingResult.success("alignment-proposal:" + proposal.proposal().id());
        } catch (AgentKnowledgeExtractionWorkflowAdapter.WorkflowGenerationException exception) {
            String code = exception.code() != null && exception.code().matches("[A-Z0-9_:-]{1,100}")
                    ? exception.code() : "ALIGNMENT_AGENT_FAILED";
            return JobHandlingResult.failure(code, "Alignment proposal generation did not complete");
        } catch (Exception exception) {
            return JobHandlingResult.failure("ALIGNMENT_PROPOSAL_INVALID",
                    "Alignment proposal generation or validation failed");
        }
    }

    private List<Evidence> evidence(Payload payload, KnowledgeIr base) {
        if (payload.action() != AlignmentAction.REGULATORY) {
            return List.of();
        }
        Set<UUID> requested = new HashSet<>(payload.regulatoryMaterialIds());
        List<TrustedContext> contexts = materialSelectionService.regulatoryContext(base.metadata().roundId(),
                base.metadata().subSceneId(), ContextBudget.defaults());
        List<Evidence> evidence = new ArrayList<>();
        for (TrustedContext trusted : contexts) {
            if (!requested.contains(trusted.selection().material().id())) continue;
            trusted.chunks().forEach(chunk -> {
                var material = trusted.selection().material();
                var locator = chunk.locator();
                KnowledgeIr.SourceRef ref = new KnowledgeIr.SourceRef(chunk.sourceRefCode(), material.id(),
                        material.sha256(), chunk.id(), locator.type().name(), locator.page(), locator.paragraph(),
                        locator.table(), locator.sheet(), locator.rowStart(), locator.rowEnd(), locator.colStart(),
                        locator.colEnd(), locator.lineStart(), locator.lineEnd(), chunk.contentHash());
                evidence.add(new Evidence(ref, chunk.content()));
            });
        }
        Set<UUID> present = evidence.stream().map(item -> item.sourceRef().materialId())
                .collect(java.util.stream.Collectors.toSet());
        if (!present.containsAll(requested)) {
            throw new IllegalStateException("one or more regulatory materials have no trusted chunks");
        }
        return List.copyOf(evidence);
    }

    private record Payload(UUID documentId, UUID baseRevisionId, String baseEtag,
            AlignmentAction action, List<UUID> regulatoryMaterialIds, UUID modelConfigVersionId,
            UUID skillVersionId, UUID roleConfigVersionId, String roleConfigHash) {
        private Payload {
            regulatoryMaterialIds = regulatoryMaterialIds == null ? List.of() : List.copyOf(regulatoryMaterialIds);
        }
    }
}
