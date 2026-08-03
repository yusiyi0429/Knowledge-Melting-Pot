package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlignmentService {
    private static final int MAX_MARKDOWN_CHARACTERS = 5_000_000;
    private static final int MAX_REASON_CHARACTERS = 20_000;
    private static final int MAX_SOURCE_REFS = 10_000;
    private static final Set<String> PATCH_FIELDS = Set.of("operation", "markdown");

    private final AlignmentProposalRepository proposalRepository;
    private final List<RegulatoryMaterialAccessPort> regulatoryMaterialPorts;
    private final DocumentService documentService;
    private final JobService jobService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AlignmentService(AlignmentProposalRepository proposalRepository,
            List<RegulatoryMaterialAccessPort> regulatoryMaterialPorts,
            DocumentService documentService, JobService jobService, AuditService auditService,
            ObjectMapper objectMapper, Clock clock) {
        this.proposalRepository = proposalRepository;
        this.regulatoryMaterialPorts = List.copyOf(regulatoryMaterialPorts);
        this.documentService = documentService;
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
    public AlignmentProposal createProposal(AlignmentProposalDraft draft, UUID actorId, String traceId) {
        if (draft == null || draft.documentId() == null || draft.baseRevisionId() == null || draft.action() == null) {
            throw new IllegalArgumentException("proposal document, base revision and action are required");
        }
        DocumentRevision baseRevision = requireRevisionForDocument(draft.documentId(), draft.baseRevisionId());
        List<UUID> materialIds = validateRegulatoryMaterials(draft.documentId(), draft.action(),
                draft.regulatoryMaterialIds());
        ReplaceMarkdownPatch patch = parsePatch(draft.structuredPatchJson());
        String reason = requireText(draft.reason(), "proposal reason", MAX_REASON_CHARACTERS);
        JsonNode sourceRefs = parseSourceRefs(draft.sourceRefsJson());
        validateRegulatorySourceRefs(draft.action(), materialIds, sourceRefs);
        Instant now = Instant.now(clock);
        AlignmentProposal proposal = new AlignmentProposal(UUID.randomUUID(), draft.documentId(),
                baseRevision.id(), baseRevision.etag(), draft.action(), AlignmentProposalStatus.READY,
                toJson(patch), reason, toJson(sourceRefs), toJson(materialIds), actorId, now,
                null, null, null);
        AlignmentProposal saved = proposalRepository.insert(proposal);
        auditService.record(actorId, "ALIGNMENT_PROPOSAL_CREATED", "ALIGNMENT_PROPOSAL", proposal.id(),
                Map.of("documentId", proposal.documentId(), "baseRevisionId", proposal.baseRevisionId(),
                        "action", proposal.action()), traceId);
        return saved;
    }

    @Transactional(readOnly = true)
    public AlignmentProposal get(UUID proposalId) {
        return proposalRepository.find(proposalId)
                .orElseThrow(() -> new NotFoundException("alignment proposal not found: " + proposalId));
    }

    @Transactional
    public DocumentRevision adopt(UUID proposalId, String ifMatch, UUID actorId, String traceId) {
        AlignmentProposal proposal = get(proposalId);
        if (proposal.status() != AlignmentProposalStatus.READY) {
            throw new ConflictException("alignment proposal has already been adopted");
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("If-Match is required when adopting an alignment proposal");
        }
        String normalizedIfMatch = ifMatch.trim();
        if (!proposal.baseEtag().equals(normalizedIfMatch)) {
            throw new PreconditionFailedException("If-Match does not match the proposal base revision");
        }
        DocumentRevision current = documentService.get(proposal.documentId());
        if (!current.id().equals(proposal.baseRevisionId()) || !current.etag().equals(proposal.baseEtag())) {
            throw new PreconditionFailedException("knowledge document has changed since the proposal was created");
        }

        ReplaceMarkdownPatch patch = parsePatch(proposal.structuredPatchJson());
        DocumentRevision adoptedRevision = documentService.save(proposal.documentId(), current.subSceneId(),
                patch.markdown(), "采纳对齐提案 " + proposal.id(), false, proposal.baseEtag(), actorId, traceId);
        if (!proposalRepository.insertAdoption(proposal.id(), adoptedRevision.id(), actorId, Instant.now(clock))) {
            throw new ConflictException("alignment proposal was adopted concurrently");
        }
        auditService.record(actorId, "ALIGNMENT_PROPOSAL_ADOPTED", "ALIGNMENT_PROPOSAL", proposal.id(),
                Map.of("documentId", proposal.documentId(), "baseRevisionId", proposal.baseRevisionId(),
                        "adoptedRevisionId", adoptedRevision.id()), traceId);
        return adoptedRevision;
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
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();
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
        for (RegulatoryMaterialAccessPort port : regulatoryMaterialPorts) {
            port.requireRegulatoryNonHoldout(documentId, materialIds);
        }
        return materialIds;
    }

    private ReplaceMarkdownPatch parsePatch(String patchJson) {
        JsonNode patchNode = parseJson(patchJson, "structured patch");
        if (!patchNode.isObject()) {
            throw new IllegalArgumentException("structured patch must be a JSON object");
        }
        Set<String> fields = new HashSet<>();
        Iterator<String> fieldNames = patchNode.fieldNames();
        fieldNames.forEachRemaining(fields::add);
        if (!PATCH_FIELDS.equals(fields)) {
            throw new IllegalArgumentException("structured patch only accepts operation and markdown");
        }
        if (!"replaceMarkdown".equals(patchNode.path("operation").asText())) {
            throw new IllegalArgumentException("only replaceMarkdown alignment patches are supported");
        }
        String markdown = requireText(patchNode.path("markdown").isTextual()
                ? patchNode.path("markdown").textValue()
                : null, "replacement markdown", MAX_MARKDOWN_CHARACTERS);
        return new ReplaceMarkdownPatch("replaceMarkdown", markdown);
    }

    private JsonNode parseSourceRefs(String sourceRefsJson) {
        JsonNode sourceRefs = parseJson(sourceRefsJson == null || sourceRefsJson.isBlank() ? "[]" : sourceRefsJson,
                "source references");
        if (!sourceRefs.isArray()) {
            throw new IllegalArgumentException("source references must be a JSON array");
        }
        if (sourceRefs.size() > MAX_SOURCE_REFS) {
            throw new IllegalArgumentException("source references exceed the processing limit");
        }
        for (JsonNode sourceRef : sourceRefs) {
            if (!sourceRef.isObject()) {
                throw new IllegalArgumentException("each source reference must be a JSON object");
            }
        }
        return sourceRefs;
    }

    private void validateRegulatorySourceRefs(AlignmentAction action, List<UUID> materialIds, JsonNode sourceRefs) {
        if (action != AlignmentAction.REGULATORY) {
            return;
        }
        if (sourceRefs.isEmpty()) {
            throw new IllegalArgumentException("REGULATORY proposal requires source references");
        }
        Set<UUID> allowedMaterialIds = Set.copyOf(materialIds);
        for (JsonNode sourceRef : sourceRefs) {
            JsonNode materialIdNode = sourceRef.get("materialId");
            if (materialIdNode == null || !materialIdNode.isTextual()) {
                throw new IllegalArgumentException("regulatory source reference requires materialId");
            }
            UUID materialId;
            try {
                materialId = UUID.fromString(materialIdNode.textValue());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("regulatory source reference has an invalid materialId", exception);
            }
            if (!allowedMaterialIds.contains(materialId)) {
                throw new IllegalArgumentException("regulatory source reference uses an unapproved material");
            }
        }
    }

    private JsonNode parseJson(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(name + " is not valid JSON", exception);
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

    private record ReplaceMarkdownPatch(String operation, String markdown) {
    }
}
