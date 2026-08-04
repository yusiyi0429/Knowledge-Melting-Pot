package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.EvaluationRepository;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.SkillEvaluationWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.TrustedContext;
import com.knowledgemeltingpot.workbench.application.service.MaterialSelectionService;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.EvaluationCase;
import com.knowledgemeltingpot.workbench.domain.EvaluationCaseResult;
import com.knowledgemeltingpot.workbench.domain.EvaluationStatus;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvaluationJobHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private EvaluationRepository evaluations;
    private MaterialSelectionService materials;
    private SkillEvaluationWorkflowPort workflow;
    private EvaluationJobHandler handler;

    @BeforeEach
    void setUp() {
        evaluations = mock(EvaluationRepository.class);
        materials = mock(MaterialSelectionService.class);
        workflow = mock(SkillEvaluationWorkflowPort.class);
        handler = new EvaluationJobHandler(evaluations, materials, workflow, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void freezesStrictHoldoutJsonlAndPersistsOnlyNormalizedPerCaseEvidence() {
        Job job = job();
        var run = run(job);
        AtomicReference<List<EvaluationCase>> frozenCases = new AtomicReference<>();
        List<EvaluationCaseResult> storedResults = new ArrayList<>();
        when(evaluations.find(run.id())).thenReturn(Optional.of(run));
        when(evaluations.markRunning(run.id(), job.id(), NOW)).thenReturn(true);
        when(evaluations.findCases(run.id())).thenAnswer(invocation ->
                frozenCases.get() == null ? List.of() : frozenCases.get());
        when(evaluations.insertCaseSet(any(), any(), any(), any())).thenAnswer(invocation -> {
            frozenCases.set(List.copyOf(invocation.getArgument(2)));
            return true;
        });
        when(evaluations.findResults(run.id())).thenReturn(List.of());
        when(evaluations.insertResult(any())).thenAnswer(invocation -> storedResults.add(invocation.getArgument(0)));
        when(evaluations.counts(run.id())).thenReturn(new EvaluationRepository.EvaluationCounts(2, 2, 0, 0));
        when(evaluations.markSucceeded(any(), any(), any(), any())).thenReturn(true);
        when(materials.evaluationContext(any(), any(), any())).thenReturn(List.of(context("""
                {"caseId":"late-120","input":"客户逾期120天","expected":"次级","tags":["风险"]}
                {"caseId":"healthy","input":"客户正常履约","expected":"正常"}
                """)));
        when(workflow.predict(any())).thenAnswer(invocation -> {
            SkillEvaluationWorkflowPort.EvaluationRequest request = invocation.getArgument(0);
            return new SkillEvaluationWorkflowPort.EvaluationPrediction(
                    request.input().contains("120") ? "  次级  " : "正常");
        });

        JobHandlingResult result = handler.handle(leased(job), context());

        assertThat(result.succeeded()).isTrue();
        assertThat(frozenCases.get()).extracting(EvaluationCase::caseKey)
                .containsExactly("late-120", "healthy");
        assertThat(storedResults).extracting(EvaluationCaseResult::prediction)
                .containsExactly("次级", "正常");
        assertThat(storedResults).extracting(value -> value.outcome().name())
                .containsOnly("PASSED");
        ArgumentCaptor<SkillEvaluationWorkflowPort.EvaluationRequest> request =
                ArgumentCaptor.forClass(SkillEvaluationWorkflowPort.EvaluationRequest.class);
        verify(workflow, org.mockito.Mockito.times(2)).predict(request.capture());
        assertThat(request.getAllValues()).allSatisfy(value -> {
            assertThat(value.input()).doesNotContain("expected", "次级\"", "正常\"");
            assertThat(value.modelConfigVersionId()).isEqualTo(run.modelConfigVersionId());
            assertThat(value.skillVersionId()).isEqualTo(run.skillVersionId());
        });
        verify(evaluations).markSucceeded(run.id(), new EvaluationRepository.EvaluationCounts(2, 2, 0, 0),
                new BigDecimal("1.000000"), NOW);
    }

    @Test
    void retryReusesFrozenCasesAndCompletedResults() {
        Job job = job();
        var run = run(job);
        EvaluationCase first = evaluationCase(run.id(), 0, "first", "第一个输入", "正常");
        EvaluationCase second = evaluationCase(run.id(), 1, "second", "逾期30天", "关注");
        EvaluationCaseResult completed = new EvaluationCaseResult(run.id(), first.id(), "正常",
                com.knowledgemeltingpot.workbench.domain.EvaluationOutcome.PASSED, "", 10, NOW);
        when(evaluations.find(run.id())).thenReturn(Optional.of(run));
        when(evaluations.markRunning(run.id(), job.id(), NOW)).thenReturn(true);
        when(evaluations.findCases(run.id())).thenReturn(List.of(first, second));
        when(evaluations.findResults(run.id())).thenReturn(List.of(completed));
        when(workflow.predict(any())).thenReturn(new SkillEvaluationWorkflowPort.EvaluationPrediction("关注"));
        when(evaluations.insertResult(any())).thenReturn(true);
        when(evaluations.counts(run.id())).thenReturn(new EvaluationRepository.EvaluationCounts(2, 2, 0, 0));
        when(evaluations.markSucceeded(any(), any(), any(), any())).thenReturn(true);

        assertThat(handler.handle(leased(job), context()).succeeded()).isTrue();

        verify(materials, never()).evaluationContext(any(), any(), any());
        ArgumentCaptor<SkillEvaluationWorkflowPort.EvaluationRequest> request =
                ArgumentCaptor.forClass(SkillEvaluationWorkflowPort.EvaluationRequest.class);
        verify(workflow).predict(request.capture());
        assertThat(request.getValue().caseId()).isEqualTo(second.id());
    }

    private WorkerJobContext context() {
        WorkerJobContext context = mock(WorkerJobContext.class);
        when(context.cancellationRequested()).thenReturn(false);
        return context;
    }

    private Job job() {
        UUID runId = UUID.randomUUID();
        return new Job(UUID.randomUUID(), JobType.EVALUATE, "EVALUATION_RUN", runId,
                JobStatus.RUNNING, 0, 1, "{}", "", "", "", UUID.randomUUID(), NOW, NOW);
    }

    private com.knowledgemeltingpot.workbench.domain.EvaluationRun run(Job job) {
        return new com.knowledgemeltingpot.workbench.domain.EvaluationRun(job.aggregateId(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), job.id(), "", EvaluationStatus.QUEUED,
                0, 0, 0, 0, null, "", job.requestedBy(), NOW, null, null, NOW);
    }

    private LeasedJob leased(Job job) {
        return new LeasedJob(job, "evaluation-worker-1", NOW.plusSeconds(60), 1);
    }

    private TrustedContext context(String content) {
        UUID materialId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        Material material = new Material(materialId, "holdout.txt", MaterialFormat.TXT, "text/plain",
                "holdout/verified.txt", "a".repeat(64), content.length(), MaterialStatus.READY, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), materialId, roundId, subSceneId,
                MaterialPartition.LABELED_HOLDOUT, MaterialShareScope.ROUND, false, true, NOW);
        MaterialChunk chunk = MaterialChunk.fromParsed(UUID.randomUUID(), UUID.randomUUID(), 0, "SRC-HOLDOUT-1",
                new ChunkLocator(ChunkLocator.LocatorType.TXT_LINES, null, null, null, null, null, null, null,
                        null, 1, 2), content, "v1", NOW);
        return new TrustedContext(new MaterialSelection(material, binding), List.of(chunk));
    }

    private EvaluationCase evaluationCase(UUID runId, int ordinal, String key, String input, String expected) {
        return new EvaluationCase(UUID.randomUUID(), runId, ordinal, key, input, expected,
                UUID.randomUUID(), UUID.randomUUID(), "SRC-HOLDOUT-" + ordinal, "d".repeat(64), List.of(), NOW);
    }
}
