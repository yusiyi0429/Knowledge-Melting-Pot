package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionRequiredException;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import com.knowledgemeltingpot.workbench.application.port.DocumentRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {
    private static final int MAX_DOCUMENT_CHARACTERS = 5_000_000;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^#{1,6}\\s+\\S");
    private static final Pattern SOURCE_ANCHOR = Pattern.compile("\\[SRC-[A-Za-z0-9_-]{1,100}]");
    private final DocumentRepository documentRepository;
    private final SceneRepository sceneRepository;
    private final AuditService auditService;
    private final Clock clock;

    public DocumentService(DocumentRepository documentRepository, SceneRepository sceneRepository,
            AuditService auditService, Clock clock) {
        this.documentRepository = documentRepository;
        this.sceneRepository = sceneRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DocumentRevision get(UUID documentId) {
        return documentRepository.findLatest(documentId)
                .orElseThrow(() -> new NotFoundException("knowledge document not found: " + documentId));
    }

    @Transactional(readOnly = true)
    public DocumentRevision getRevision(UUID revisionId) {
        return documentRepository.findRevision(revisionId)
                .orElseThrow(() -> new NotFoundException("document revision not found: " + revisionId));
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
        validateMarkdown(content, finalize);
        DocumentRevision current = documentRepository.findLatest(documentId).orElse(null);
        UUID subSceneId = current == null ? requestedSubSceneId : current.subSceneId();
        if (subSceneId == null) {
            throw new IllegalArgumentException("subSceneId is required when creating a knowledge document");
        }
        sceneRepository.findSubScene(subSceneId)
                .orElseThrow(() -> new NotFoundException("sub-scene not found: " + subSceneId));
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
        auditService.record(actorId, "DOCUMENT_REVISION_SAVED", "KNOWLEDGE_DOCUMENT", documentId,
                Map.of("revision", saved.revision(), "contentHash", saved.contentHash(),
                        "finalized", saved.finalized()), traceId);
        return saved;
    }

    private void validateMarkdown(String content, boolean finalize) {
        if (content == null || content.isBlank()) {
            throw new UnprocessableEntityException("document Markdown must not be blank");
        }
        if (content.length() > MAX_DOCUMENT_CHARACTERS) {
            throw new UnprocessableEntityException("document Markdown exceeds the 5000000 character limit");
        }
        if (content.indexOf('\0') >= 0) {
            throw new UnprocessableEntityException("document Markdown contains a forbidden null character");
        }
        if (finalize && !MARKDOWN_HEADING.matcher(content).find()) {
            throw new UnprocessableEntityException("a finalized document requires a Markdown heading");
        }
        if (finalize && !SOURCE_ANCHOR.matcher(content).find()) {
            throw new UnprocessableEntityException("a finalized document requires at least one [SRC-*] anchor");
        }
    }
}
