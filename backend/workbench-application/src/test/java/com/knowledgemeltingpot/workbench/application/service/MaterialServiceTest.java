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
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.ExplorationSession;
import com.knowledgemeltingpot.workbench.domain.ExplorationStatus;
import com.knowledgemeltingpot.workbench.domain.ExtractionRoundStatus;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
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
    private ExplorationRepository explorations;
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
        service = new MaterialService(materials, explorations, scenes, idempotency, jobs, audit, Optional.empty(),
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
    void stagesExplorationMaterialWithoutCreatingAPrematureRoundBinding() {
        UUID sessionId = UUID.randomUUID();
        ExplorationSession session = new ExplorationSession(sessionId, "风险场景探索", ExplorationStatus.DRAFT,
                null, null, null, null, "", 0, ACTOR_ID, NOW, NOW);
        when(explorations.find(sessionId)).thenReturn(Optional.of(session));
        when(explorations.linkMaterial(eq(sessionId), any(), eq(NOW))).thenReturn(true);
        when(materials.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(materials.insertIntent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MaterialUploadIntentResult result = service.createUploadIntent(new MaterialUploadCommand(
                "explore.txt", 12, "text/plain", "a".repeat(64), null, Set.of(),
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false, sessionId),
                ACTOR_ID, null, "trace");

        assertThat(result.bindings()).isEmpty();
        verify(materials).insertBindings(List.of());
        verify(explorations).linkMaterial(sessionId, result.material().id(), NOW);
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

    @Test
    void workbenchListingReturnsEveryMaterialStatusForRoundAndSubScene() {
        Material uploaded = new Material(UUID.randomUUID(), "notes.txt", MaterialFormat.TXT, "text/plain",
                "quarantine/" + UUID.randomUUID(), "b".repeat(64), 12, MaterialStatus.UPLOADED, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), uploaded.id(), round.id(), primary.id(),
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false, true, NOW);
        when(materials.findWorkbenchMaterials(round.id(), primary.id()))
                .thenReturn(List.of(new MaterialSelection(uploaded, binding)));

        List<MaterialSelection> result = service.listWorkbenchMaterials(round.id(), primary.id());

        assertThat(result).singleElement().satisfies(selection -> {
            assertThat(selection.material().status()).isEqualTo(MaterialStatus.UPLOADED);
            assertThat(selection.binding().subSceneId()).isEqualTo(primary.id());
            assertThat(selection.binding().active()).isTrue();
        });
    }

    @Test
    void workbenchListingRejectsRoundThatDoesNotBelongToSubScene() {
        UUID otherSceneId = UUID.randomUUID();
        SubScene other = new SubScene(UUID.randomUUID(), otherSceneId, "Other", "", NOW, NOW);
        lenient().when(scenes.findSubScene(other.id())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.listWorkbenchMaterials(round.id(), other.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("round does not belong");
    }

    @Test
    void abortUploadMovesPendingMaterialToInactiveTerminalState() {
        UUID materialId = UUID.randomUUID();
        MaterialUploadIntent intent = MaterialUploadIntent.multipart(UUID.randomUUID(), materialId, ACTOR_ID, NOW,
                "upload-1", "quarantine/" + materialId, 5_242_880L, 1, NOW.plusSeconds(900));
        when(materials.lockIntent(intent.id())).thenReturn(Optional.of(intent));
        when(materials.abortIntent(intent.id(), NOW)).thenReturn(true);

        service.abortUpload(intent.id(), ACTOR_ID, "trace");

        verify(materials).transitionStatus(materialId, MaterialStatus.PENDING_UPLOAD,
                MaterialStatus.INACTIVE, NOW);
    }

    @Test
    void deactivatesOneBindingWithoutDeletingSharedImmutableMaterial() {
        UUID materialId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        Material material = new Material(materialId, "rules.docx", MaterialFormat.DOCX,
                MaterialFormat.DOCX.mediaType(), "quarantine/" + materialId, "a".repeat(64), 10,
                MaterialStatus.FAILED, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(bindingId, materialId, round.id(), primary.id(),
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, true, true, NOW);
        when(materials.findById(materialId)).thenReturn(Optional.of(material));
        when(materials.findBindings(materialId)).thenReturn(List.of(binding));
        when(materials.deactivateBinding(materialId, bindingId)).thenReturn(true);

        service.deactivateBinding(materialId, bindingId, ACTOR_ID, "trace");

        verify(materials).deactivateBinding(materialId, bindingId);
        verify(audit).record(eq(ACTOR_ID), eq("MATERIAL_BINDING_DEACTIVATED"), eq("MATERIAL"),
                eq(materialId), anyMap(), eq("trace"));
        verify(materials, never()).transitionStatus(eq(materialId), any(), any(), any());
    }

    @Test
    void rejectsBindingThatDoesNotBelongToMaterial() {
        UUID materialId = UUID.randomUUID();
        Material material = new Material(materialId, "rules.pdf", MaterialFormat.PDF,
                MaterialFormat.PDF.mediaType(), "quarantine/" + materialId, "a".repeat(64), 10,
                MaterialStatus.READY, NOW, NOW);
        when(materials.findById(materialId)).thenReturn(Optional.of(material));
        when(materials.findBindings(materialId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.deactivateBinding(materialId, UUID.randomUUID(), ACTOR_ID, "trace"))
                .isInstanceOf(com.knowledgemeltingpot.workbench.application.error.NotFoundException.class)
                .hasMessageContaining("binding");

        verify(materials, never()).deactivateBinding(any(), any());
    }
}
