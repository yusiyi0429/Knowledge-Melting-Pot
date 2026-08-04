package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.port.AlignmentProposalRepository;
import com.knowledgemeltingpot.workbench.application.port.RegulatoryMaterialAccessPort;
import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposal;
import com.knowledgemeltingpot.workbench.domain.AlignmentProposalStatus;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
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
    private ObjectMapper mapper;
    private KnowledgeMarkdownCodec codec;
    private KnowledgeDiffCalculator diff;

    @BeforeEach
    void setUp() {
        proposals = mock(AlignmentProposalRepository.class);
        documents = mock(DocumentService.class);
        jobs = mock(JobService.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        KnowledgeIrValidator validator = new KnowledgeIrValidator(mapper);
        codec = new KnowledgeMarkdownCodec(mapper, validator);
        diff = new KnowledgeDiffCalculator();
    }

    @Test
    void queueUsesTypedIdentifiersAndFreezesTheBaseProjection() {
        UUID documentId = UUID.randomUUID();
        DocumentRevision base = revision(documentId, UUID.randomUUID(), 3);
        KnowledgeIr ir = ir(documentId, base.subSceneId(), UUID.randomUUID(), List.of());
        when(documents.getRevision(base.id())).thenReturn(base);
        when(documents.get(documentId)).thenReturn(base);
        when(documents.getProjection(base.id())).thenReturn(ir);
        Job queued = queuedJob(documentId);
        when(jobs.submit(eq(JobType.ALIGN), eq("KNOWLEDGE_DOCUMENT"), eq(documentId), anyMap(),
                eq(ACTOR_ID), eq("idem-1"), eq("trace-1"))).thenReturn(new JobSubmission(queued, false));

        JobSubmission submission = service(List.of()).queue(documentId,
                new AlignmentJobCommand(base.id(), AlignmentAction.CONSISTENCY, List.of()),
                ACTOR_ID, "idem-1", "trace-1");

        assertThat(submission.job()).isEqualTo(queued);
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(jobs).submit(eq(JobType.ALIGN), eq("KNOWLEDGE_DOCUMENT"), eq(documentId), payload.capture(),
                eq(ACTOR_ID), eq("idem-1"), eq("trace-1"));
        assertThat(payload.getValue()).containsOnlyKeys("documentId", "baseRevisionId", "baseEtag", "action",
                "regulatoryMaterialIds");
        assertThat(payload.getValue().toString()).doesNotContain("content", "prompt", "markdown");
    }

    @Test
    void proposalStoresTypedPatchAndStructuredDiff() {
        UUID documentId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        DocumentRevision baseRevision = revision(documentId, subSceneId, 2);
        KnowledgeIr base = ir(documentId, subSceneId, UUID.randomUUID(), List.of());
        KnowledgeIr replacement = new KnowledgeIr(base.schemaVersion(), base.metadata(), base.rules(), base.flows(),
                base.conflicts(), List.of(new KnowledgeIr.Gap("G-1", "缺少异常处理")), base.sourceRefs());
        when(documents.getRevision(baseRevision.id())).thenReturn(baseRevision);
        when(documents.getProjection(baseRevision.id())).thenReturn(base);
        when(proposals.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AlignmentProposalView view = service(List.of()).createProposal(new AlignmentProposalDraft(documentId,
                baseRevision.id(), AlignmentAction.GAP_ANALYSIS, replacement, "发现一项缺失", List.of()),
                ACTOR_ID, "trace-2");

        assertThat(view.proposal().status()).isEqualTo(AlignmentProposalStatus.READY);
        assertThat(view.patch().operation()).isEqualTo(KnowledgePatch.REPLACE_OPERATION);
        assertThat(view.patch().replacement().gaps()).hasSize(1);
        assertThat(view.patch().diff().sourceRefDelta()).isZero();
        assertThat(view.proposal().structuredPatchJson()).contains("replaceKnowledgeIr", "缺少异常处理");
    }

    @Test
    void regulatoryProposalRequiresApprovedMaterialForEveryNewReference() {
        UUID documentId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        DocumentRevision baseRevision = revision(documentId, UUID.randomUUID(), 1);
        KnowledgeIr base = ir(documentId, baseRevision.subSceneId(), UUID.randomUUID(), List.of());
        KnowledgeIr.SourceRef ref = source(materialId);
        KnowledgeIr replacement = new KnowledgeIr(base.schemaVersion(), base.metadata(), base.rules(), base.flows(),
                base.conflicts(), base.gaps(), List.of(ref));
        when(documents.getRevision(baseRevision.id())).thenReturn(baseRevision);
        when(documents.getProjection(baseRevision.id())).thenReturn(base);

        assertThatThrownBy(() -> service(List.of()).createProposal(new AlignmentProposalDraft(documentId,
                baseRevision.id(), AlignmentAction.REGULATORY, replacement, "监管补充", List.of(materialId)),
                ACTOR_ID, "trace-3"))
                .isInstanceOf(ConflictException.class);

        RegulatoryMaterialAccessPort eligibility = mock(RegulatoryMaterialAccessPort.class);
        when(proposals.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AlignmentProposalView view = service(List.of(eligibility)).createProposal(new AlignmentProposalDraft(
                documentId, baseRevision.id(), AlignmentAction.REGULATORY, replacement, "监管补充",
                List.of(materialId)), ACTOR_ID, "trace-3");
        assertThat(view.regulatoryMaterialIds()).containsExactly(materialId);
        verify(eligibility).requireRegulatoryNonHoldout(documentId, List.of(materialId));
    }

    @Test
    void adoptionUsesProposalBaseEtagAndRejectsAChangedDocument() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        DocumentRevision baseRevision = revision(documentId, subSceneId, 5);
        KnowledgeIr base = ir(documentId, subSceneId, UUID.randomUUID(), List.of());
        KnowledgePatch patch = new KnowledgePatch(KnowledgePatch.REPLACE_OPERATION, base, diff.compare(base, base));
        AlignmentProposal proposal = new AlignmentProposal(UUID.randomUUID(), documentId, baseRevision.id(),
                baseRevision.etag(), AlignmentAction.REWRITE, AlignmentProposalStatus.READY,
                mapper.writeValueAsString(patch), "统一术语", "[]", "[]", ACTOR_ID, NOW, null, null, null);
        when(proposals.find(proposal.id())).thenReturn(Optional.of(proposal));
        when(documents.get(documentId)).thenReturn(revision(documentId, subSceneId, 6));

        assertThatThrownBy(() -> service(List.of()).adopt(proposal.id(), baseRevision.etag(), ACTOR_ID, "trace-4"))
                .isInstanceOf(PreconditionFailedException.class)
                .hasMessageContaining("changed since");
    }

    private AlignmentService service(List<RegulatoryMaterialAccessPort> ports) {
        return new AlignmentService(proposals, ports, documents, codec, diff, jobs, mock(AuditService.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private KnowledgeIr ir(UUID documentId, UUID subSceneId, UUID roundId, List<KnowledgeIr.SourceRef> refs) {
        return new KnowledgeIr(KnowledgeIr.SCHEMA_VERSION,
                new KnowledgeIr.Metadata(documentId, subSceneId, roundId, "a".repeat(64)),
                List.of(), List.of(), List.of(), List.of(), refs);
    }

    private KnowledgeIr.SourceRef source(UUID materialId) {
        return new KnowledgeIr.SourceRef("SRC-REG-1", materialId, "b".repeat(64), UUID.randomUUID(),
                "TXT_LINES", null, null, null, null, null, null, null, null, 1, 1, "c".repeat(64));
    }

    private DocumentRevision revision(UUID documentId, UUID subSceneId, long number) {
        String hash = String.format("%064d", number);
        return new DocumentRevision(UUID.randomUUID(), documentId, subSceneId, number, null, "# r" + number,
                hash, "", false, null, null, ACTOR_ID, NOW.minusSeconds(60));
    }

    private Job queuedJob(UUID documentId) {
        return new Job(UUID.randomUUID(), JobType.ALIGN, "KNOWLEDGE_DOCUMENT", documentId, JobStatus.QUEUED, 0,
                0, "{}", "", "", "", ACTOR_ID, NOW, NOW);
    }
}
