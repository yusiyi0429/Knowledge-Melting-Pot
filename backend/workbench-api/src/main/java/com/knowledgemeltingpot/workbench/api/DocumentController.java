package com.knowledgemeltingpot.workbench.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
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
        DocumentRevision revision = documentService.get(documentId);
        return ResponseEntity.ok().eTag(revision.etag()).body(KnowledgeDocumentResponse.from(revision));
    }

    @PutMapping("/{documentId}")
    public ResponseEntity<KnowledgeDocumentResponse> save(@PathVariable UUID documentId,
            @Valid @RequestBody SaveDocumentRequest body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        DocumentRevision revision = documentService.save(documentId, body.subSceneId(), body.contentMd(),
                body.revisionNote(), body.finalizeRevision(), ifMatch, currentUser.id(authentication),
                RequestIdFilter.currentTraceId());
        return ResponseEntity.ok().eTag(revision.etag()).body(KnowledgeDocumentResponse.from(revision));
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

        static KnowledgeDocumentResponse from(DocumentRevision revision) {
            return new KnowledgeDocumentResponse(revision.documentId(), revision.subSceneId(), revision.id(),
                    revision.revision(), revision.content(), revision.contentHash(), revision.finalized(),
                    List.of(), revision.etag());
        }
    }

    public record SourceRefResponse(UUID materialId, String materialSha256, String locator, String excerptHash) {
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
