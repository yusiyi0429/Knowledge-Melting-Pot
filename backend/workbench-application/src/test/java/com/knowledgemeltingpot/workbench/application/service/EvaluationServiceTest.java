package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.port.EvaluationRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.ReleaseItemSnapshot;
import com.knowledgemeltingpot.workbench.application.port.ReleaseRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import com.knowledgemeltingpot.workbench.domain.Release;
import com.knowledgemeltingpot.workbench.domain.ReleaseCoverage;
import com.knowledgemeltingpot.workbench.domain.ReleaseItemDisposition;
import com.knowledgemeltingpot.workbench.domain.ReleaseStatus;
import com.knowledgemeltingpot.workbench.domain.ReleaseSubSceneStatus;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.math.BigDecimal;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvaluationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    private EvaluationRepository evaluations;
    private ReleaseRepository releases;
    private SceneRepository scenes;
    private MaterialSelectionService materials;
    private JobService jobs;
    private AuditService audit;
    private ObjectMapper objectMapper;
    private EvaluationService service;

    @BeforeEach
    void setUp() {
        evaluations = mock(EvaluationRepository.class);
        releases = mock(ReleaseRepository.class);
        scenes = mock(SceneRepository.class);
        materials = mock(MaterialSelectionService.class);
        jobs = mock(JobService.class);
        audit = mock(AuditService.class);
        objectMapper = evaluationObjectMapper();
        service = new EvaluationService(evaluations, releases, scenes, materials, jobs, audit,
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
        when(evaluations.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void requestFreezesPublishedEvaluatorModelSkillAndReleaseAssets() throws Exception {
        Fixture fixture = givenEvaluableRelease();
        when(jobs.submit(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            UUID aggregateId = invocation.getArgument(2);
            UUID actor = invocation.getArgument(4);
            return new JobSubmission(new Job(UUID.randomUUID(), JobType.EVALUATE, "EVALUATION_RUN", aggregateId,
                    JobStatus.QUEUED, 0, 0, "{}", "", "", "", actor, NOW, NOW), false);
        });

        var submission = service.request(fixture.releaseId, fixture.subSceneId, fixture.roundId,
                fixture.actorId, "evaluation-001", "trace-evaluation");

        assertThat(submission.run().releaseId()).isEqualTo(fixture.releaseId);
        assertThat(submission.run().documentRevisionId()).isEqualTo(fixture.revisionId);
        assertThat(submission.run().evaluationAssetId()).isEqualTo(fixture.evaluationAssetId);
        assertThat(submission.run().skillAssetId()).isEqualTo(fixture.skillAssetId);
        assertThat(submission.run().modelConfigVersionId()).isEqualTo(fixture.modelVersionId);
        assertThat(submission.run().skillVersionId()).isEqualTo(fixture.skillVersionId);
        assertThat(submission.run().jobId()).isEqualTo(submission.job().job().id());
        ArgumentCaptor<java.util.Map<String, Object>> payload = ArgumentCaptor.forClass(java.util.Map.class);
        verify(jobs).submit(org.mockito.ArgumentMatchers.eq(JobType.EVALUATE),
                org.mockito.ArgumentMatchers.eq("EVALUATION_RUN"), any(), payload.capture(),
                org.mockito.ArgumentMatchers.eq(fixture.actorId),
                org.mockito.ArgumentMatchers.eq("evaluation-001"),
                org.mockito.ArgumentMatchers.eq("trace-evaluation"));
        assertThat(payload.getValue()).containsEntry("releaseId", fixture.releaseId)
                .containsEntry("subSceneId", fixture.subSceneId)
                .containsEntry("roundId", fixture.roundId);
    }

    @Test
    void requestIsBlockedBeforeJobSubmissionWithoutServerSelectedHoldout() throws Exception {
        Fixture fixture = givenEvaluableRelease();
        when(materials.forEvaluation(fixture.roundId, fixture.subSceneId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.request(fixture.releaseId, fixture.subSceneId, fixture.roundId,
                fixture.actorId, null, "trace-no-holdout"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("LABELED_HOLDOUT");
        verify(jobs, never()).submit(any(), anyString(), any(), any(), any(), any(), any());
    }

    private Fixture givenEvaluableRelease() throws Exception {
        UUID releaseId = UUID.randomUUID();
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID evaluationAssetId = UUID.randomUUID();
        UUID skillAssetId = UUID.randomUUID();
        UUID modelVersionId = UUID.randomUUID();
        UUID skillVersionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ReleaseManifest manifest = new ReleaseManifest("1.0", releaseId, sceneId, "v1.0.0",
                ReleaseCoverage.FULL, "发布", NOW, null, List.of(new ReleaseManifest.SubSceneEntry(
                        subSceneId, ReleaseSubSceneStatus.SELECTED, null, revisionId, List.of(),
                        List.of(new ReleaseManifest.AgentConfigurationEntry(AgentRole.QA_EVALUATOR, true,
                                "a".repeat(64), UUID.randomUUID(),
                                new ReleaseManifest.ModelEntry(modelVersionId, UUID.randomUUID(),
                                        ModelProvider.DASHSCOPE, "qwen-plus", 1, new BigDecimal("0.1"), 256),
                                new ReleaseManifest.SkillEntry(skillVersionId, UUID.randomUUID(),
                                        SkillKind.TEMPLATE, 1, "b".repeat(64)), List.of())))), "c".repeat(64));
        Release release = new Release(releaseId, sceneId, "v1.0.0", ReleaseStatus.PUBLISHED,
                ReleaseCoverage.FULL, "发布", null, objectMapper.writeValueAsString(manifest),
                "c".repeat(64), actorId, NOW, NOW);
        when(releases.find(releaseId)).thenReturn(Optional.of(release));
        when(releases.findItems(releaseId)).thenReturn(List.of(
                new ReleaseItemSnapshot(evaluationAssetId, subSceneId, AssetType.EVALUATION_SET, 1,
                        revisionId, "evaluation.jsonl", "e".repeat(64), ReleaseItemDisposition.SELECTED, releaseId),
                new ReleaseItemSnapshot(skillAssetId, subSceneId, AssetType.SKILL_PACKAGE, 1,
                        revisionId, "skill.zip", "f".repeat(64), ReleaseItemDisposition.SELECTED, releaseId)));
        when(scenes.findSubScene(subSceneId)).thenReturn(Optional.of(
                new SubScene(subSceneId, sceneId, "贷后风险", "", NOW, NOW)));
        when(scenes.findRound(roundId)).thenReturn(Optional.of(
                new ExtractionRound(roundId, subSceneId, 1, ExtractionRoundStatus.DRAFT, NOW, NOW)));
        when(materials.forEvaluation(roundId, subSceneId)).thenReturn(List.of(mock(MaterialSelection.class)));
        return new Fixture(releaseId, subSceneId, roundId, revisionId, evaluationAssetId, skillAssetId,
                modelVersionId, skillVersionId, actorId);
    }

    private record Fixture(UUID releaseId, UUID subSceneId, UUID roundId, UUID revisionId,
            UUID evaluationAssetId, UUID skillAssetId, UUID modelVersionId, UUID skillVersionId,
            UUID actorId) { }

    private ObjectMapper evaluationObjectMapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return Instant.parse(parser.getValueAsString());
            }
        });
        return new ObjectMapper().registerModule(module);
    }
}
