package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.ExtractionRunRepository;
import com.knowledgemeltingpot.workbench.application.port.FrozenExtractionChunk;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort.KnowledgeDraft;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort.RuleDraft;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.application.service.KnowledgeIrValidator;
import com.knowledgemeltingpot.workbench.application.service.KnowledgeMarkdownCodec;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.ExtractionRun;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentExtractionJobHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private KnowledgeExtractionWorkflowPort workflow;
    private ExtractionRunRepository runs;
    private DocumentService documents;
    private ObjectMapper mapper;
    private AgentExtractionJobHandler handler;

    @BeforeEach
    void setUp() {
        workflow = mock(KnowledgeExtractionWorkflowPort.class);
        runs = mock(ExtractionRunRepository.class);
        documents = mock(DocumentService.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        KnowledgeIrValidator validator = new KnowledgeIrValidator(mapper);
        handler = new AgentExtractionJobHandler(workflow, runs, validator,
                new KnowledgeMarkdownCodec(mapper, validator), documents, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void refusesAJobWithoutItsFrozenSnapshot() {
        Job job = job();
        when(runs.findByJobId(job.id())).thenReturn(Optional.empty());

        JobHandlingResult result = handler.handle(leased(job), context());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EXTRACTION_SNAPSHOT_MISSING");
        verify(workflow, never()).map(any());
    }

    @Test
    void mapsReducesAndPersistsAValidatedRevision() {
        Job job = job();
        ExtractionRun run = run(job);
        FrozenExtractionChunk chunk = chunk();
        KnowledgeDraft draft = draft(chunk.sourceRef().code());
        when(runs.findByJobId(job.id())).thenReturn(Optional.of(run));
        when(runs.findChunks(run.id())).thenReturn(List.of(chunk));
        when(runs.findMapResult(run.id(), chunk.chunk().id())).thenReturn(Optional.empty());
        when(workflow.map(any())).thenReturn(draft);
        when(workflow.reduce(any())).thenReturn(draft);
        when(documents.save(any(), any(), anyString(), anyString(), any(Boolean.class), anyString(), any(), anyString()))
                .thenReturn(new DocumentRevision(UUID.randomUUID(), run.documentId(), run.subSceneId(), 1, null,
                        "validated", "d".repeat(64), "", false, null, null, job.requestedBy(), NOW));

        JobHandlingResult result = handler.handle(leased(job), context());

        assertThat(result.succeeded()).isTrue();
        verify(runs).insertMapResult(any(), any(), anyString(), anyString(), any());
        verify(runs).insertReduceResult(any(), any(KnowledgeIr.class), anyString(), any());
        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        verify(documents).save(any(), any(), markdown.capture(), anyString(), any(Boolean.class),
                anyString(), any(), anyString());
        assertThat(markdown.getValue()).contains("```kmp-metadata", "```kmp-rule", "[SRC-TEST-1]");
    }

    @Test
    void reusesAnImmutableMapCheckpointOnRetry() throws Exception {
        Job job = job();
        ExtractionRun run = run(job);
        FrozenExtractionChunk chunk = chunk();
        KnowledgeDraft draft = draft(chunk.sourceRef().code());
        when(runs.findByJobId(job.id())).thenReturn(Optional.of(run));
        when(runs.findChunks(run.id())).thenReturn(List.of(chunk));
        when(runs.findMapResult(run.id(), chunk.chunk().id()))
                .thenReturn(Optional.of(mapper.writeValueAsString(draft)));
        when(workflow.reduce(any())).thenReturn(draft);
        when(documents.save(any(), any(), anyString(), anyString(), any(Boolean.class), anyString(), any(), anyString()))
                .thenReturn(new DocumentRevision(UUID.randomUUID(), run.documentId(), run.subSceneId(), 1, null,
                        "validated", "d".repeat(64), "", false, null, null, job.requestedBy(), NOW));

        assertThat(handler.handle(leased(job), context()).succeeded()).isTrue();
        verify(workflow, never()).map(any());
        verify(workflow).reduce(any());
    }

    private WorkerJobContext context() {
        WorkerJobContext context = mock(WorkerJobContext.class);
        when(context.cancellationRequested()).thenReturn(false);
        return context;
    }

    private Job job() {
        return new Job(UUID.randomUUID(), JobType.EXTRACT, "SUB_SCENE", UUID.randomUUID(),
                JobStatus.RUNNING, 1, 1, "{}", "", "", "", UUID.randomUUID(), NOW, NOW);
    }

    private LeasedJob leased(Job job) {
        return new LeasedJob(job, "worker-1", NOW.plusSeconds(60), 1);
    }

    private ExtractionRun run(Job job) {
        return new ExtractionRun(UUID.randomUUID(), job.id(), job.aggregateId(), job.aggregateId(), UUID.randomUUID(),
                null, null, UUID.randomUUID(), UUID.randomUUID(), null, null, "a".repeat(64), ExtractionRun.Stage.FROZEN,
                job.requestedBy(), NOW, NOW);
    }

    private FrozenExtractionChunk chunk() {
        UUID materialId = UUID.randomUUID();
        MaterialChunk chunk = MaterialChunk.fromParsed(UUID.randomUUID(), UUID.randomUUID(), 0, "SRC-TEST-1",
                new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES, null, null, null, null, null, null, null,
                        null, 1, 2), "verified source", "1", NOW);
        KnowledgeIr.SourceRef ref = new KnowledgeIr.SourceRef(chunk.sourceRefCode(), materialId, "b".repeat(64),
                chunk.id(), "TXT_LINES", null, null, null, null, null, null, null, null, 1, 2,
                chunk.contentHash());
        return new FrozenExtractionChunk(materialId, "b".repeat(64), MaterialPartition.SOURCE, chunk, ref);
    }

    private KnowledgeDraft draft(String sourceRef) {
        return new KnowledgeDraft(List.of(new RuleDraft("测试规则", "条件", "结论", 10, List.of(),
                List.of(sourceRef))), List.of(), List.of(), List.of());
    }
}
