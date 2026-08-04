package com.knowledgemeltingpot.workbench.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-documents")
public class DocumentController {
    private final DocumentService documentService;
    private final CurrentUser currentUser;

    public DocumentController(DocumentService documentService, CurrentUser currentUser) {
        this.documentService = documentService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<KnowledgeDocumentResponse> get(@PathVariable UUID documentId) {
        DocumentService.KnowledgeDocumentView view = documentService.getView(documentId);
        return ResponseEntity.ok().eTag(view.revision().etag()).body(KnowledgeDocumentResponse.from(view));
    }

    @PutMapping("/{documentId}")
    public ResponseEntity<KnowledgeDocumentResponse> save(@PathVariable UUID documentId,
            @Valid @RequestBody SaveDocumentRequest body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        DocumentRevision revision = documentService.save(documentId, body.subSceneId(), body.contentMd(),
                body.revisionNote(), body.finalizeRevision(), ifMatch, currentUser.id(authentication),
                RequestIdFilter.currentTraceId());
        return ResponseEntity.ok().eTag(revision.etag())
                .body(KnowledgeDocumentResponse.from(documentService.getView(documentId)));
    }

    @GetMapping("/{documentId}/revisions")
    public List<DocumentRevisionSummary> revisions(@PathVariable UUID documentId) {
        return documentService.revisions(documentId).stream().map(DocumentRevisionSummary::from).toList();
    }

    public record SaveDocumentRequest(
            UUID subSceneId,
            @NotNull @Size(min = 1, max = 5_000_000) String contentMd,
            @Size(max = 500) String revisionNote,
            @JsonProperty("finalize") boolean finalizeRevision) {
    }

    public record KnowledgeDocumentResponse(
            UUID id,
            UUID subSceneId,
            UUID revisionId,
            long revisionNumber,
            String contentMd,
            String contentHash,
            boolean finalized,
            List<SourceRefResponse> sourceRefs,
            String etag) {

        public static KnowledgeDocumentResponse from(DocumentService.KnowledgeDocumentView view) {
            DocumentRevision revision = view.revision();
            return new KnowledgeDocumentResponse(revision.documentId(), revision.subSceneId(), revision.id(),
                    revision.revision(), revision.content(), revision.contentHash(), revision.finalized(),
                    view.sourceRefs().stream().map(SourceRefResponse::from).toList(), revision.etag());
        }

        /** Kept for isolated response-shape tests and legacy revisions without a projection. */
        static KnowledgeDocumentResponse from(DocumentRevision revision) {
            return from(new DocumentService.KnowledgeDocumentView(revision, null));
        }
    }

    public record SourceRefResponse(
            String code,
            UUID materialId,
            String materialSha256,
            UUID chunkId,
            String locatorType,
            Integer page,
            Integer paragraph,
            Integer table,
            String sheet,
            Integer rowStart,
            Integer rowEnd,
            Integer colStart,
            Integer colEnd,
            Integer lineStart,
            Integer lineEnd,
            String excerptHash) {
        static SourceRefResponse from(KnowledgeIr.SourceRef ref) {
            return new SourceRefResponse(ref.code(), ref.materialId(), ref.materialSha256(), ref.chunkId(),
                    ref.locatorType(), ref.page(), ref.paragraph(), ref.table(), ref.sheet(), ref.rowStart(),
                    ref.rowEnd(), ref.colStart(), ref.colEnd(), ref.lineStart(), ref.lineEnd(), ref.excerptHash());
        }
    }

    public record DocumentRevisionSummary(
            UUID id,
            long revisionNumber,
            String contentHash,
            String note,
            UUID createdBy,
            boolean finalized,
            Instant createdAt) {

        static DocumentRevisionSummary from(DocumentRevision revision) {
            String note = revision.revisionNote().isBlank() ? null : revision.revisionNote();
            return new DocumentRevisionSummary(revision.id(), revision.revision(), revision.contentHash(), note,
                    revision.createdBy(), revision.finalized(), revision.createdAt());
        }
    }
}
