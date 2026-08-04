package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionRequiredException;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import com.knowledgemeltingpot.workbench.application.port.ChunkRepository;
import com.knowledgemeltingpot.workbench.application.port.DocumentRepository;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeProjectionRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSourceRef;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {
    private static final int MAX_DOCUMENT_CHARACTERS = 5_000_000;
    private final DocumentRepository documentRepository;
    private final SceneRepository sceneRepository;
    private final KnowledgeProjectionRepository projectionRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeMarkdownCodec markdownCodec;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DocumentService(DocumentRepository documentRepository, SceneRepository sceneRepository,
            KnowledgeProjectionRepository projectionRepository, ChunkRepository chunkRepository,
            KnowledgeMarkdownCodec markdownCodec, AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.documentRepository = documentRepository;
        this.sceneRepository = sceneRepository;
        this.projectionRepository = projectionRepository;
        this.chunkRepository = chunkRepository;
        this.markdownCodec = markdownCodec;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DocumentRevision get(UUID documentId) {
        return find(documentId)
                .orElseThrow(() -> new NotFoundException("knowledge document not found: " + documentId));
    }

    /** Optional lookup for workflows where the first revision is a normal, non-error state. */
    @Transactional(readOnly = true)
    public Optional<DocumentRevision> find(UUID documentId) {
        return documentRepository.findLatest(documentId);
    }

    @Transactional(readOnly = true)
    public DocumentRevision getRevision(UUID revisionId) {
        return documentRepository.findRevision(revisionId)
                .orElseThrow(() -> new NotFoundException("document revision not found: " + revisionId));
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentView getView(UUID documentId) {
        DocumentRevision revision = get(documentId);
        return new KnowledgeDocumentView(revision, projectionRepository.find(revision.id()).orElse(null));
    }

    @Transactional(readOnly = true)
    public KnowledgeIr getProjection(UUID revisionId) {
        getRevision(revisionId);
        return projectionRepository.find(revisionId)
                .orElseThrow(() -> new NotFoundException("KnowledgeIR projection not found: " + revisionId));
    }

    @Transactional(readOnly = true)
    public void validateProjection(UUID documentId, KnowledgeIr ir) {
        DocumentRevision current = get(documentId);
        markdownCodec.render(ir);
        if (!ir.metadata().documentId().equals(documentId)
                || !ir.metadata().subSceneId().equals(current.subSceneId())) {
            throw new UnprocessableEntityException("knowledge-ir-metadata-mismatch",
                    "KnowledgeIR metadata does not match the requested document and sub-scene");
        }
        validatePersistedSourceRefs(ir);
    }

    @Transactional(readOnly = true)
    public List<DocumentRevision> revisions(UUID documentId) {
        get(documentId);
        return documentRepository.findRevisions(documentId);
    }

    @Transactional
    public DocumentRevision save(UUID documentId, UUID subSceneId, String content, String ifMatch,
            UUID actorId, String traceId) {
        return save(documentId, subSceneId, content, "", false, ifMatch, actorId, traceId);
    }

    @Transactional
    public DocumentRevision save(UUID documentId, UUID requestedSubSceneId, String content, String revisionNote,
            boolean finalize, String ifMatch, UUID actorId, String traceId) {
        validateEnvelope(content);
        KnowledgeIr ir = markdownCodec.parse(content);
        DocumentRevision current = documentRepository.findLatest(documentId).orElse(null);
        UUID subSceneId = current == null ? requestedSubSceneId : current.subSceneId();
        if (subSceneId == null) {
            throw new IllegalArgumentException("subSceneId is required when creating a knowledge document");
        }
        sceneRepository.findSubScene(subSceneId)
                .orElseThrow(() -> new NotFoundException("sub-scene not found: " + subSceneId));
        if (!ir.metadata().documentId().equals(documentId) || !ir.metadata().subSceneId().equals(subSceneId)) {
            throw new UnprocessableEntityException("knowledge-ir-metadata-mismatch",
                    "KnowledgeIR metadata does not match the requested document and sub-scene");
        }
        validatePersistedSourceRefs(ir);
        if (finalize && ir.sourceRefs().isEmpty()) {
            throw new UnprocessableEntityException("knowledge-ir-source-required",
                    "A finalized document requires at least one persisted source reference");
        }
        Long expectedRevision = null;
        if (current != null) {
            if (ifMatch == null || ifMatch.isBlank()) {
                throw new PreconditionRequiredException("If-Match is required when updating a document");
            }
            if (!current.etag().equals(ifMatch.trim())) {
                throw new PreconditionFailedException("knowledge document has a newer revision");
            }
            if (requestedSubSceneId != null && !current.subSceneId().equals(requestedSubSceneId)) {
                throw new PreconditionFailedException("document cannot move to another sub-scene");
            }
            expectedRevision = current.revision();
        } else if (ifMatch != null && !ifMatch.isBlank() && !"*".equals(ifMatch.trim())) {
            throw new PreconditionFailedException("knowledge document does not exist");
        }

        Instant now = Instant.now(clock);
        DocumentRevision saved = documentRepository.saveNextRevision(documentId, subSceneId, expectedRevision,
                UUID.randomUUID(), content, Hashes.sha256(content), revisionNote, finalize, actorId, now);
        projectionRepository.insert(saved.id(), ir, canonicalIrHash(ir), now);
        auditService.record(actorId, "DOCUMENT_REVISION_SAVED", "KNOWLEDGE_DOCUMENT", documentId,
                Map.of("revision", saved.revision(), "contentHash", saved.contentHash(),
                        "finalized", saved.finalized()), traceId);
        return saved;
    }

    private void validateEnvelope(String content) {
        if (content == null || content.isBlank()) {
            throw new UnprocessableEntityException("document Markdown must not be blank");
        }
        if (content.length() > MAX_DOCUMENT_CHARACTERS) {
            throw new UnprocessableEntityException("document Markdown exceeds the 5000000 character limit");
        }
        if (content.indexOf('\0') >= 0) {
            throw new UnprocessableEntityException("document Markdown contains a forbidden null character");
        }
    }

    private void validatePersistedSourceRefs(KnowledgeIr ir) {
        List<String> codes = ir.sourceRefs().stream().map(KnowledgeIr.SourceRef::code).toList();
        List<MaterialSourceRef> persisted = chunkRepository.findTrustedSourceRefs(
                ir.metadata().roundId(), ir.metadata().subSceneId(), codes);
        var allowed = persisted.stream().map(this::toKnowledgeSourceRef).collect(java.util.stream.Collectors.toSet());
        for (KnowledgeIr.SourceRef ref : ir.sourceRefs()) {
            if (!allowed.contains(ref)) {
                throw new UnprocessableEntityException("knowledge-ir-source-invalid",
                        "Source reference " + ref.code() + " is not a READY non-HOLDOUT chunk of this round");
            }
        }
        if (allowed.size() < ir.sourceRefs().size()) {
            throw new UnprocessableEntityException("knowledge-ir-source-invalid",
                    "One or more source references cannot be resolved uniquely");
        }
    }

    private KnowledgeIr.SourceRef toKnowledgeSourceRef(MaterialSourceRef ref) {
        return new KnowledgeIr.SourceRef(ref.code(), ref.materialId(), ref.materialSha256(), ref.chunkId(),
                ref.locatorType(), ref.page(), ref.paragraph(), ref.table(), ref.sheet(), ref.rowStart(), ref.rowEnd(),
                ref.colStart(), ref.colEnd(), ref.lineStart(), ref.lineEnd(), ref.excerptHash());
    }

    private String canonicalIrHash(KnowledgeIr ir) {
        try {
            return Hashes.sha256(objectMapper.writeValueAsString(ir));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KnowledgeIR could not be hashed", exception);
        }
    }

    public record KnowledgeDocumentView(DocumentRevision revision, KnowledgeIr projection) {
        public List<KnowledgeIr.SourceRef> sourceRefs() {
            return projection == null ? List.of() : projection.sourceRefs();
        }
    }
}
