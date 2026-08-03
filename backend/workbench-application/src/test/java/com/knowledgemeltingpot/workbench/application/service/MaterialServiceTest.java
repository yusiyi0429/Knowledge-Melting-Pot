package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.IdempotencyRecord;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.MaterialUploadIntent;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import com.knowledgemeltingpot.workbench.domain.UploadState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Mock
    private MaterialRepository materials;
    @Mock
    private SceneRepository scenes;
    @Mock
    private IdempotencyRepository idempotency;
    @Mock
    private JobService jobs;
    @Mock
    private AuditService audit;
    private MaterialService service;
    private ExtractionRound round;
    private SubScene primary;

    @BeforeEach
    void setUp() {
        service = new MaterialService(materials, scenes, idempotency, jobs, audit, Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        UUID sceneId = UUID.randomUUID();
        primary = new SubScene(UUID.randomUUID(), sceneId, "Primary", "", NOW, NOW);
        round = new ExtractionRound(UUID.randomUUID(), primary.id(), 1, ExtractionRoundStatus.DRAFT, NOW, NOW);
        lenient().when(scenes.findRound(round.id())).thenReturn(Optional.of(round));
        lenient().when(scenes.findSubScene(primary.id())).thenReturn(Optional.of(primary));
    }

    @Test
    void createsGlobalMaterialAndPartitionedBindingWithoutPretendingUploadSucceeded() {
        when(materials.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(materials.insertIntent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MaterialUploadIntentResult result = service.createUploadIntent(command(MaterialPartition.SOURCE,
                MaterialShareScope.ROUND, false, Set.of()), ACTOR_ID, null, "trace");

        assertThat(result.material().status()).isEqualTo(MaterialStatus.PENDING_UPLOAD);
        assertThat(result.material().objectKey()).startsWith("quarantine/");
        assertThat(result.uploadMode()).isEqualTo("DECLARATION_ONLY");
        assertThat(result.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.roundId()).isEqualTo(round.id());
            assertThat(binding.subSceneId()).isEqualTo(primary.id());
            assertThat(binding.partition()).isEqualTo(MaterialPartition.SOURCE);
        });
        verify(materials).insertBindings(result.bindings());
    }

    @Test
    void sceneSharingMayBindOnlySubScenesFromTheSameScene() {
        SubScene second = new SubScene(UUID.randomUUID(), primary.sceneId(), "Second", "", NOW, NOW);
        when(scenes.findSubScene(second.id())).thenReturn(Optional.of(second));
        when(materials.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(materials.insertIntent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MaterialUploadIntentResult result = service.createUploadIntent(command(MaterialPartition.LABELED_TRAIN,
                MaterialShareScope.SCENE, false, Set.of(primary.id(), second.id())), ACTOR_ID, null, "trace");

        assertThat(result.bindings()).hasSize(2).extracting(RoundMaterial::subSceneId)
                .containsExactlyInAnyOrder(primary.id(), second.id());
    }

    @Test
    void holdoutRegulatorySourceIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.createUploadIntent(command(MaterialPartition.LABELED_HOLDOUT,
                MaterialShareScope.ROUND, true, Set.of()), ACTOR_ID, null, "trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holdout");

        verify(materials, never()).insert(any());
    }

    @Test
    void createIntentReplaysSameResourceForSameIdempotencyKey() {
        String key = "material-key-001";
        when(idempotency.find("material-upload:" + ACTOR_ID, key)).thenReturn(Optional.empty());
        when(idempotency.tryReserve(any())).thenReturn(true);
        when(materials.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(materials.insertIntent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MaterialUploadCommand command = command(MaterialPartition.SOURCE, MaterialShareScope.ROUND, false, Set.of());

        MaterialUploadIntentResult first = service.createUploadIntent(command, ACTOR_ID, key, "trace");
        ArgumentCaptor<IdempotencyRecord> reservation = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotency).tryReserve(reservation.capture());
        when(idempotency.find("material-upload:" + ACTOR_ID, key))
                .thenReturn(Optional.of(reservation.getValue()));
        when(materials.findIntent(first.intent().id())).thenReturn(Optional.of(first.intent()));
        when(materials.findById(first.material().id())).thenReturn(Optional.of(first.material()));
        when(materials.findBindings(first.material().id())).thenReturn(first.bindings());

        MaterialUploadIntentResult replay = service.createUploadIntent(command, ACTOR_ID, key, "trace");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.intent().id()).isEqualTo(first.intent().id());
        verify(materials, times(1)).insert(any());
    }

    @Test
    void completeIsIdempotentAndOnlyQueuesValidationJob() {
        UUID intentId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Material material = new Material(materialId, "rules.pdf", com.knowledgemeltingpot.workbench.domain.MaterialFormat.PDF,
                "application/pdf", "quarantine/" + materialId, "a".repeat(64), 10,
                MaterialStatus.PENDING_UPLOAD, NOW, NOW);
        MaterialUploadIntent pending = MaterialUploadIntent.declarationOnly(intentId, materialId, ACTOR_ID, NOW);
        MaterialUploadIntent completed = pending.completed(jobId, "", NOW);
        Job job = new Job(jobId, JobType.INGEST, "MATERIAL", materialId, JobStatus.QUEUED, 0, 0, "{}", "", "", "",
                ACTOR_ID, NOW, NOW);
        when(materials.lockIntent(intentId)).thenReturn(Optional.of(pending), Optional.of(completed));
        when(materials.findById(materialId)).thenReturn(Optional.of(material));
        when(materials.transitionStatus(materialId, MaterialStatus.PENDING_UPLOAD, MaterialStatus.UPLOADED, NOW))
                .thenReturn(true);
        when(materials.incrementCompletionAttempt(intentId)).thenReturn(true);
        when(jobs.submit(eq(JobType.INGEST), eq("MATERIAL"), eq(materialId), anyMap(), eq(ACTOR_ID),
                eq(null), eq("trace"))).thenReturn(new JobSubmission(job, false));
        when(materials.completeIntent(intentId, jobId, "", NOW)).thenReturn(true);
        when(jobs.get(jobId)).thenReturn(job);

        JobSubmission first = service.completeUpload(intentId, List.of(), ACTOR_ID, "trace");
        JobSubmission replay = service.completeUpload(intentId, List.of(), ACTOR_ID, "trace");

        assertThat(first.job().id()).isEqualTo(jobId);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.job().id()).isEqualTo(jobId);
        assertThat(replay.replayed()).isTrue();
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(jobs, times(1)).submit(eq(JobType.INGEST), eq("MATERIAL"), eq(materialId), payload.capture(),
                eq(ACTOR_ID), eq(null), eq("trace"));
        assertThat(payload.getValue().get("expectedSha256")).isEqualTo("a".repeat(64));
        assertThat(payload.getValue().get("format")).isEqualTo(com.knowledgemeltingpot.workbench.domain.MaterialFormat.PDF);
    }

    private MaterialUploadCommand command(MaterialPartition partition, MaterialShareScope shareScope,
            boolean regulatorySource, Set<UUID> subSceneIds) {
        return new MaterialUploadCommand("rules.pdf", 10, "application/pdf", "a".repeat(64), round.id(),
                subSceneIds, partition, shareScope, regulatorySource);
    }
}
