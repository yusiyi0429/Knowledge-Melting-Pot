package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionRequiredException;
import com.knowledgemeltingpot.workbench.application.port.AlignmentProposalRepository;
import com.knowledgemeltingpot.workbench.application.port.RegulatoryMaterialAccessPort;
import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposal;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposalStatus;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlignmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private AlignmentProposalRepository proposals;
    private DocumentService documents;
    private JobService jobs;
    private AuditService audit;
    private AlignmentService service;

    @BeforeEach
    void setUp() {
        proposals = mock(AlignmentProposalRepository.class);
        documents = mock(DocumentService.class);
        jobs = mock(JobService.class);
        audit = mock(AuditService.class);
        service = service(List.of());
    }

    @Test
    void queueAcceptsOnlyTypedIdentifiersAndProducesControlledPayload() {
        UUID documentId = UUID.randomUUID();
        DocumentRevision base = revision(documentId, UUID.randomUUID(), 3);
        Job queued = queuedJob(documentId);
        when(documents.getRevision(base.id())).thenReturn(base);
        when(documents.get(documentId)).thenReturn(base);
        when(jobs.submit(eq(JobType.ALIGN), eq("KNOWLEDGE_DOCUMENT"), eq(documentId), anyMap(),
                eq(ACTOR_ID), eq("idem-1"), eq("trace-1"))).thenReturn(new JobSubmission(queued, false));

        JobSubmission submission = service.queue(documentId,
                new AlignmentJobCommand(base.id(), AlignmentAction.CONSISTENCY, List.of()),
                ACTOR_ID, "idem-1", "trace-1");

        assertThat(submission.job()).isEqualTo(queued);
        ArgumentCaptor<Map<String, ?>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jobs).submit(eq(JobType.ALIGN), eq("KNOWLEDGE_DOCUMENT"), eq(documentId),
                payloadCaptor.capture(), eq(ACTOR_ID), eq("idem-1"), eq("trace-1"));
        assertThat(payloadCaptor.getValue()).containsOnlyKeys(
                "documentId", "baseRevisionId", "baseEtag", "action", "regulatoryMaterialIds");
        assertThat(payloadCaptor.getValue().toString()).doesNotContain("markdown", "prompt", "content");
    }

    @Test
    void regulatoryQueuePreservesIdsAndRequiresEligibilityPort() {
        UUID documentId = UUID.randomUUID();
        DocumentRevision base = revision(documentId, UUID.randomUUID(), 1);
        UUID materialB = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID materialA = UUID.fromString("00000000-0000-0000-0000-000000000010");
        when(documents.getRevision(base.id())).thenReturn(base);
        when(documents.get(documentId)).thenReturn(base);

        assertThatThrownBy(() -> service.queue(documentId,
                new AlignmentJobCommand(base.id(), AlignmentAction.REGULATORY, List.of(materialB, materialA)),
                ACTOR_ID, null, "trace-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("policy is unavailable");

        RegulatoryMaterialAccessPort eligibility = mock(RegulatoryMaterialAccessPort.class);
        AlignmentService authorized = service(List.of(eligibility));
        when(jobs.submit(any(), any(), any(), anyMap(), any(), any(), any()))
                .thenReturn(new JobSubmission(queuedJob(documentId), false));
        authorized.queue(documentId,
                new AlignmentJobCommand(base.id(), AlignmentAction.REGULATORY, List.of(materialB, materialA)),
                ACTOR_ID, null, "trace-2");

        verify(eligibility).requireRegulatoryNonHoldout(documentId, List.of(materialA, materialB));
    }

    @Test
    void proposalIsStoredAgainstServerDerivedBaseEtagWithCanonicalLimitedPatch() {
        UUID documentId = UUID.randomUUID();
        DocumentRevision base = revision(documentId, UUID.randomUUID(), 2);
        when(documents.getRevision(base.id())).thenReturn(base);
        when(proposals.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AlignmentProposal proposal = service.createProposal(new AlignmentProposalDraft(documentId, base.id(),
                AlignmentAction.REWRITE,
                "{\"markdown\":\"# 新版\",\"operation\":\"replaceMarkdown\"}",
                "补齐例外规则", "[]", List.of()), ACTOR_ID, "trace-3");

        assertThat(proposal.status()).isEqualTo(AlignmentProposalStatus.READY);
        assertThat(proposal.baseEtag()).isEqualTo(base.etag());
        assertThat(proposal.structuredPatchJson())
                .isEqualTo("{\"operation\":\"replaceMarkdown\",\"markdown\":\"# 新版\"}");
        assertThat(proposal.adoptedRevisionId()).isNull();
    }

    @Test
    void unsupportedOrOpenEndedPatchIsRejectedBeforePersistence() {
        UUID documentId = UUID.randomUUID();
        DocumentRevision base = revision(documentId, UUID.randomUUID(), 2);
        when(documents.getRevision(base.id())).thenReturn(base);

        assertThatThrownBy(() -> service.createProposal(new AlignmentProposalDraft(documentId, base.id(),
                AlignmentAction.REWRITE,
                "{\"operation\":\"merge\",\"markdown\":\"# 新版\",\"arbitrary\":true}",
                "原因", "[]", List.of()), ACTOR_ID, "trace-4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only accepts operation and markdown");
        verify(proposals, never()).insert(any());
    }

    @Test
    void adoptRequiresMatchingHeaderAndCurrentRevisionThenCreatesANewRevision() {
        UUID documentId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        DocumentRevision base = revision(documentId, subSceneId, 5);
        AlignmentProposal proposal = readyProposal(documentId, base);
        DocumentRevision next = revision(documentId, subSceneId, 6);
        when(proposals.find(proposal.id())).thenReturn(Optional.of(proposal));
        when(documents.get(documentId)).thenReturn(base);
        when(documents.save(documentId, subSceneId, "# 已采纳", "采纳对齐提案 " + proposal.id(), false,
                base.etag(), ACTOR_ID, "trace-5")).thenReturn(next);
        when(proposals.insertAdoption(proposal.id(), next.id(), ACTOR_ID, NOW)).thenReturn(true);

        DocumentRevision adopted = service.adopt(proposal.id(), base.etag(), ACTOR_ID, "trace-5");

        assertThat(adopted).isEqualTo(next);
        verify(documents).save(documentId, subSceneId, "# 已采纳", "采纳对齐提案 " + proposal.id(), false,
                base.etag(), ACTOR_ID, "trace-5");
        verify(proposals).insertAdoption(proposal.id(), next.id(), ACTOR_ID, NOW);
    }

    @Test
    void adoptRejectsMissingOrMismatchedIfMatchBeforeWriting() {
        UUID documentId = UUID.randomUUID();
        DocumentRevision base = revision(documentId, UUID.randomUUID(), 1);
        AlignmentProposal proposal = readyProposal(documentId, base);
        when(proposals.find(proposal.id())).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> service.adopt(proposal.id(), null, ACTOR_ID, "trace-6"))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> service.adopt(proposal.id(), "\"different\"", ACTOR_ID, "trace-6"))
                .isInstanceOf(PreconditionFailedException.class);
        verify(documents, never()).save(any(), any(), any(), any(), eq(false), any(), any(), any());
    }

    @Test
    void staleOrAlreadyAdoptedProposalCannotOverwriteTheDocument() {
        UUID documentId = UUID.randomUUID();
        DocumentRevision base = revision(documentId, UUID.randomUUID(), 1);
        AlignmentProposal ready = readyProposal(documentId, base);
        when(proposals.find(ready.id())).thenReturn(Optional.of(ready));
        when(documents.get(documentId)).thenReturn(revision(documentId, base.subSceneId(), 2));

        assertThatThrownBy(() -> service.adopt(ready.id(), base.etag(), ACTOR_ID, "trace-7"))
                .isInstanceOf(PreconditionFailedException.class)
                .hasMessageContaining("changed since");

        AlignmentProposal adopted = new AlignmentProposal(ready.id(), ready.documentId(), ready.baseRevisionId(),
                ready.baseEtag(), ready.action(), AlignmentProposalStatus.ADOPTED, ready.structuredPatchJson(),
                ready.reason(), ready.sourceRefsJson(), ready.regulatoryMaterialIdsJson(), ready.createdBy(),
                ready.createdAt(), UUID.randomUUID(), ACTOR_ID, NOW);
        when(proposals.find(ready.id())).thenReturn(Optional.of(adopted));
        assertThatThrownBy(() -> service.adopt(ready.id(), base.etag(), ACTOR_ID, "trace-7"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been adopted");
        verify(documents, never()).save(any(), any(), any(), any(), eq(false), any(), any(), any());
    }

    private AlignmentService service(List<RegulatoryMaterialAccessPort> ports) {
        return new AlignmentService(proposals, ports, documents, jobs, audit, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DocumentRevision revision(UUID documentId, UUID subSceneId, long number) {
        String hash = String.format("%064d", number);
        return new DocumentRevision(UUID.randomUUID(), documentId, subSceneId, number, null, "# r" + number,
                hash, "", false, null, null, ACTOR_ID, NOW.minusSeconds(60));
    }

    private AlignmentProposal readyProposal(UUID documentId, DocumentRevision base) {
        return new AlignmentProposal(UUID.randomUUID(), documentId, base.id(), base.etag(), AlignmentAction.REWRITE,
                AlignmentProposalStatus.READY,
                "{\"operation\":\"replaceMarkdown\",\"markdown\":\"# 已采纳\"}",
                "采用统一术语", "[]", "[]", ACTOR_ID, NOW.minusSeconds(30), null, null, null);
    }

    private Job queuedJob(UUID documentId) {
        return new Job(UUID.randomUUID(), JobType.ALIGN, "KNOWLEDGE_DOCUMENT", documentId, JobStatus.QUEUED, 0,
                0, "{}", "", "", "", ACTOR_ID, NOW, NOW);
    }
}
