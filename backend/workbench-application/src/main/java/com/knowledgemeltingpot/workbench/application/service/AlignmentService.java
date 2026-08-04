package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionRequiredException;
import com.knowledgemeltingpot.workbench.application.port.AlignmentProposalRepository;
import com.knowledgemeltingpot.workbench.application.port.RegulatoryMaterialAccessPort;
import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposal;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposalStatus;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlignmentService {
    private static final int MAX_REASON_CHARACTERS = 20_000;
    private final AlignmentProposalRepository proposalRepository;
    private final List<RegulatoryMaterialAccessPort> regulatoryMaterialPorts;
    private final DocumentService documentService;
    private final KnowledgeMarkdownCodec markdownCodec;
    private final KnowledgeDiffCalculator diffCalculator;
    private final JobService jobService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AlignmentService(AlignmentProposalRepository proposalRepository,
            List<RegulatoryMaterialAccessPort> regulatoryMaterialPorts,
            DocumentService documentService, KnowledgeMarkdownCodec markdownCodec,
            KnowledgeDiffCalculator diffCalculator, JobService jobService, AuditService auditService,
            ObjectMapper objectMapper, Clock clock) {
        this.proposalRepository = proposalRepository;
        this.regulatoryMaterialPorts = List.copyOf(regulatoryMaterialPorts);
        this.documentService = documentService;
        this.markdownCodec = markdownCodec;
        this.diffCalculator = diffCalculator;
        this.jobService = jobService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public JobSubmission queue(UUID documentId, AlignmentJobCommand command, UUID actorId,
            String idempotencyKey, String traceId) {
        DocumentRevision baseRevision = requireRevisionForDocument(documentId, command.baseRevisionId());
        DocumentRevision current = documentService.get(documentId);
        if (!current.id().equals(baseRevision.id())) {
            throw new PreconditionFailedException("alignment base is not the current document revision");
        }
        documentService.getProjection(baseRevision.id());
        List<UUID> materialIds = validateRegulatoryMaterials(documentId, command.action(),
                command.regulatoryMaterialIds());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId);
        payload.put("baseRevisionId", baseRevision.id());
        payload.put("baseEtag", baseRevision.etag());
        payload.put("action", command.action());
        payload.put("regulatoryMaterialIds", materialIds);
        return jobService.submit(JobType.ALIGN, "KNOWLEDGE_DOCUMENT", documentId, payload, actorId,
                idempotencyKey, traceId);
    }

    @Transactional
    public AlignmentProposalView createProposal(AlignmentProposalDraft draft, UUID actorId, String traceId) {
        if (draft == null || draft.documentId() == null || draft.baseRevisionId() == null
                || draft.action() == null || draft.replacement() == null) {
            throw new IllegalArgumentException("proposal document, base revision, action and replacement are required");
        }
        DocumentRevision baseRevision = requireRevisionForDocument(draft.documentId(), draft.baseRevisionId());
        KnowledgeIr base = documentService.getProjection(baseRevision.id());
        KnowledgeIr replacement = draft.replacement();
        if (!base.metadata().equals(replacement.metadata())) {
            throw new IllegalArgumentException("alignment replacement must preserve KnowledgeIR metadata");
        }
        documentService.validateProjection(draft.documentId(), replacement);
        List<UUID> materialIds = validateRegulatoryMaterials(draft.documentId(), draft.action(),
                draft.regulatoryMaterialIds());
        validateNewRegulatorySources(draft.action(), materialIds, base, replacement);
        String reason = requireText(draft.reason(), "proposal reason", MAX_REASON_CHARACTERS);
        KnowledgePatch patch = new KnowledgePatch(KnowledgePatch.REPLACE_OPERATION, replacement,
                diffCalculator.compare(base, replacement));
        Instant now = Instant.now(clock);
        AlignmentProposal proposal = new AlignmentProposal(UUID.randomUUID(), draft.documentId(), baseRevision.id(),
                baseRevision.etag(), draft.action(), AlignmentProposalStatus.READY, toJson(patch), reason,
                toJson(replacement.sourceRefs()), toJson(materialIds), actorId, now, null, null, null);
        AlignmentProposal saved = proposalRepository.insert(proposal);
        auditService.record(actorId, "ALIGNMENT_PROPOSAL_CREATED", "ALIGNMENT_PROPOSAL", proposal.id(),
                Map.of("documentId", proposal.documentId(), "baseRevisionId", proposal.baseRevisionId(),
                        "action", proposal.action()), traceId);
        return new AlignmentProposalView(saved, patch, replacement.sourceRefs(), materialIds);
    }

    @Transactional(readOnly = true)
    public AlignmentProposalView get(UUID proposalId) {
        AlignmentProposal proposal = proposalRepository.find(proposalId)
                .orElseThrow(() -> new NotFoundException("alignment proposal not found: " + proposalId));
        return toView(proposal);
    }

    @Transactional(readOnly = true)
    public List<AlignmentProposalView> list(UUID documentId) {
        documentService.get(documentId);
        return proposalRepository.findByDocument(documentId).stream().map(this::toView).toList();
    }

    @Transactional
    public DocumentRevision adopt(UUID proposalId, String ifMatch, UUID actorId, String traceId) {
        AlignmentProposalView view = get(proposalId);
        AlignmentProposal proposal = view.proposal();
        if (proposal.status() != AlignmentProposalStatus.READY) {
            throw new ConflictException("alignment proposal has already been adopted");
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("If-Match is required when adopting an alignment proposal");
        }
        if (!proposal.baseEtag().equals(ifMatch.trim())) {
            throw new PreconditionFailedException("If-Match does not match the proposal base revision");
        }
        DocumentRevision current = documentService.get(proposal.documentId());
        if (!current.id().equals(proposal.baseRevisionId()) || !current.etag().equals(proposal.baseEtag())) {
            throw new PreconditionFailedException("knowledge document has changed since the proposal was created");
        }

        String markdown = markdownCodec.render(view.patch().replacement());
        DocumentRevision adoptedRevision = documentService.save(proposal.documentId(), current.subSceneId(),
                markdown, "采纳对齐提案 " + proposal.id(), false, proposal.baseEtag(), actorId, traceId);
        if (!proposalRepository.insertAdoption(proposal.id(), adoptedRevision.id(), actorId, Instant.now(clock))) {
            throw new ConflictException("alignment proposal was adopted concurrently");
        }
        auditService.record(actorId, "ALIGNMENT_PROPOSAL_ADOPTED", "ALIGNMENT_PROPOSAL", proposal.id(),
                Map.of("documentId", proposal.documentId(), "baseRevisionId", proposal.baseRevisionId(),
                        "adoptedRevisionId", adoptedRevision.id()), traceId);
        return adoptedRevision;
    }

    private AlignmentProposalView toView(AlignmentProposal proposal) {
        try {
            KnowledgePatch patch = objectMapper.readValue(proposal.structuredPatchJson(), KnowledgePatch.class);
            List<KnowledgeIr.SourceRef> refs = objectMapper.readValue(proposal.sourceRefsJson(),
                    new TypeReference<>() { });
            List<UUID> materialIds = objectMapper.readValue(proposal.regulatoryMaterialIdsJson(),
                    new TypeReference<>() { });
            return new AlignmentProposalView(proposal, patch, refs, materialIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted alignment proposal is invalid", exception);
        }
    }

    private DocumentRevision requireRevisionForDocument(UUID documentId, UUID revisionId) {
        DocumentRevision revision = documentService.getRevision(revisionId);
        if (!revision.documentId().equals(documentId)) {
            throw new IllegalArgumentException("document revision does not belong to the requested document");
        }
        return revision;
    }

    private List<UUID> validateRegulatoryMaterials(UUID documentId, AlignmentAction action, List<UUID> requestedIds) {
        if (requestedIds != null && requestedIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("regulatory material IDs must be non-null");
        }
        List<UUID> materialIds = requestedIds == null ? List.of() : requestedIds.stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();
        if (new HashSet<>(materialIds).size() != materialIds.size()) {
            throw new IllegalArgumentException("regulatory material IDs must be unique");
        }
        if (action != AlignmentAction.REGULATORY) {
            if (!materialIds.isEmpty()) {
                throw new IllegalArgumentException("only REGULATORY alignment accepts regulatory material IDs");
            }
            return materialIds;
        }
        if (materialIds.isEmpty()) {
            throw new IllegalArgumentException("REGULATORY alignment requires regulatory material IDs");
        }
        if (regulatoryMaterialPorts.isEmpty()) {
            throw new ConflictException("regulatory material eligibility policy is unavailable");
        }
        regulatoryMaterialPorts.forEach(port -> port.requireRegulatoryNonHoldout(documentId, materialIds));
        return materialIds;
    }

    private void validateNewRegulatorySources(AlignmentAction action, List<UUID> allowedMaterialIds,
            KnowledgeIr base, KnowledgeIr replacement) {
        Set<KnowledgeIr.SourceRef> baseRefs = Set.copyOf(base.sourceRefs());
        Set<UUID> allowed = Set.copyOf(allowedMaterialIds);
        for (KnowledgeIr.SourceRef ref : replacement.sourceRefs()) {
            if (baseRefs.contains(ref)) {
                continue;
            }
            if (action != AlignmentAction.REGULATORY || !allowed.contains(ref.materialId())) {
                throw new IllegalArgumentException("alignment introduced an unapproved source reference");
            }
        }
    }

    private String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("alignment data is not serializable", exception);
        }
    }
}
