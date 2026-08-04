package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.AlignmentJobCommand;
import com.knowledgemeltingpot.workbench.application.service.AlignmentService;
import com.knowledgemeltingpot.workbench.application.service.AlignmentProposalView;
import com.knowledgemeltingpot.workbench.application.service.KnowledgePatch;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.application.service.JobSubmission;
import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposal;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposalStatus;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import com.knowledgemeltingpot.workbench.domain.Job;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AlignmentController {
    private final AlignmentService alignmentService;
    private final DocumentService documentService;
    private final CurrentUser currentUser;

    public AlignmentController(AlignmentService alignmentService, DocumentService documentService,
            CurrentUser currentUser) {
        this.alignmentService = alignmentService;
        this.documentService = documentService;
        this.currentUser = currentUser;
    }

    @PostMapping("/knowledge-documents/{documentId}/alignment-jobs")
    public ResponseEntity<AcceptedAlignmentJob> queue(@PathVariable UUID documentId,
            @Valid @RequestBody StartAlignmentRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        AlignmentJobCommand command = new AlignmentJobCommand(body.baseRevisionId(), body.action(),
                body.regulatoryMaterialIds());
        JobSubmission submission = alignmentService.queue(documentId, command, currentUser.id(authentication),
                idempotencyKey, RequestIdFilter.currentTraceId());
        Job job = submission.job();
        URI status = URI.create("/api/v1/jobs/" + job.id());
        return ResponseEntity.accepted()
                .location(status)
                .header("X-Idempotent-Replay", Boolean.toString(submission.replayed()))
                .body(new AcceptedAlignmentJob(job.id(), job.status().name(), status.toString(),
                        status + "/events"));
    }

    @PostMapping("/alignment-proposals/{proposalId}/adopt")
    public ResponseEntity<DocumentController.KnowledgeDocumentResponse> adopt(@PathVariable UUID proposalId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        DocumentRevision revision = alignmentService.adopt(proposalId, ifMatch, currentUser.id(authentication),
                RequestIdFilter.currentTraceId());
        return ResponseEntity.ok().eTag(revision.etag())
                .body(DocumentController.KnowledgeDocumentResponse.from(
                        documentService.getView(revision.documentId())));
    }

    @GetMapping("/alignment-proposals/{proposalId}")
    public AlignmentProposalResponse get(@PathVariable UUID proposalId) {
        return AlignmentProposalResponse.from(alignmentService.get(proposalId));
    }

    @GetMapping("/knowledge-documents/{documentId}/alignment-proposals")
    public List<AlignmentProposalResponse> list(@PathVariable UUID documentId) {
        return alignmentService.list(documentId).stream().map(AlignmentProposalResponse::from).toList();
    }

    public record StartAlignmentRequest(
            @NotNull UUID baseRevisionId,
            @NotNull AlignmentAction action,
            @Size(max = 100) List<@NotNull UUID> regulatoryMaterialIds) {
    }

    public record AcceptedAlignmentJob(UUID jobId, String status, String statusUrl, String eventsUrl) {
    }

    public record AlignmentProposalResponse(
            UUID id,
            UUID documentId,
            UUID baseRevisionId,
            String baseEtag,
            AlignmentAction action,
            AlignmentProposalStatus status,
            KnowledgePatch structuredPatch,
            String reason,
            List<KnowledgeIr.SourceRef> sourceRefs,
            List<UUID> regulatoryMaterialIds,
            UUID createdBy,
            Instant createdAt,
            UUID adoptedRevisionId,
            UUID adoptedBy,
            Instant adoptedAt) {

        static AlignmentProposalResponse from(AlignmentProposalView view) {
            AlignmentProposal proposal = view.proposal();
            return new AlignmentProposalResponse(proposal.id(), proposal.documentId(), proposal.baseRevisionId(),
                    proposal.baseEtag(), proposal.action(), proposal.status(), view.patch(),
                    proposal.reason(), view.sourceRefs(), view.regulatoryMaterialIds(),
                    proposal.createdBy(), proposal.createdAt(), proposal.adoptedRevisionId(), proposal.adoptedBy(),
                    proposal.adoptedAt());
        }
    }
}
