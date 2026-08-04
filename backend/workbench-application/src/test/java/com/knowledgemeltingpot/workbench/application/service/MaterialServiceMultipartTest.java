package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
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
import com.knowledgemeltingpot.workbench.domain.SubScene;
import com.knowledgemeltingpot.workbench.domain.UploadState;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialServiceMultipartTest {
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
    @Mock
    private ObjectStoragePort objectStorage;
    private MaterialService service;
    private ExtractionRound round;
    private SubScene primary;

    @BeforeEach
    void setUp() {
        service = new MaterialService(materials, explorations, scenes, idempotency, jobs, audit, Optional.of(objectStorage),
                Clock.fixed(NOW, ZoneOffset.UTC));
        UUID sceneId = UUID.randomUUID();
        primary = new SubScene(UUID.randomUUID(), sceneId, "Primary", "", NOW, NOW);
        round = new ExtractionRound(UUID.randomUUID(), primary.id(), 1, ExtractionRoundStatus.DRAFT, NOW, NOW);
        lenient().when(scenes.findRound(round.id())).thenReturn(Optional.of(round));
        lenient().when(scenes.findSubScene(primary.id())).thenReturn(Optional.of(primary));
    }

    @Test
    void createsMultipartUploadAndReturnsPresignedUrls() throws MalformedURLException {
        lenient().when(idempotency.find(any(), any())).thenReturn(Optional.empty());
        lenient().when(idempotency.tryReserve(any())).thenReturn(true);
        lenient().when(materials.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(materials.insertIntent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectStorage.initiateMultipart(eq(ObjectStoragePort.StorageZone.QUARANTINE), any(), any(), anyLong(), any()))
                .thenReturn(new ObjectStoragePort.MultipartUpload("upload-1", "quarantine/" + UUID.randomUUID(),
                        8L * 1024 * 1024, 2, NOW.plus(Duration.ofMinutes(15))));
        when(objectStorage.presignParts(any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(
                        new ObjectStoragePort.PresignedPart(1, new URL("http://localhost/part1"), Map.of()),
                        new ObjectStoragePort.PresignedPart(2, new URL("http://localhost/part2"), Map.of())));

        MaterialUploadIntentResult result = service.createUploadIntent(command(), ACTOR_ID, null, "trace");

        assertThat(result.objectStorageConfigured()).isTrue();
        assertThat(result.uploadMode()).isEqualTo("MULTIPART_PRESIGNED");
        assertThat(result.presignedParts()).hasSize(2);
        assertThat(result.presignedParts().get(0).partNumber()).isEqualTo(1);
        assertThat(result.presignedParts().get(1).requiredHeaders()).isEmpty();
        assertThat(result.intent().uploadState()).isEqualTo(UploadState.INITIATED);
        assertThat(result.intent().partSize()).isEqualTo(8L * 1024 * 1024);
    }

    @Test
    void completesMultipartUploadAndQueuesIngestJob() {
        UUID intentId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String quarantineKey = "quarantine/" + materialId;
        Material material = new Material(materialId, "rules.pdf", com.knowledgemeltingpot.workbench.domain.MaterialFormat.PDF,
                "application/pdf", quarantineKey, "a".repeat(64), 16,
                MaterialStatus.PENDING_UPLOAD, NOW, NOW);
        MaterialUploadIntent intent = MaterialUploadIntent.multipart(intentId, materialId, ACTOR_ID, NOW,
                "upload-1", quarantineKey, 8L * 1024 * 1024, 2, NOW.plus(Duration.ofMinutes(15)));
        MaterialUploadIntent completed = intent.completed(jobId, "\"combined-etag\"", NOW);
        Job job = new Job(jobId, JobType.INGEST, "MATERIAL", materialId, JobStatus.QUEUED, 0, 0, "{}", "", "", "",
                ACTOR_ID, NOW, NOW);
        when(materials.lockIntent(intentId)).thenReturn(Optional.of(intent), Optional.of(completed));
        when(materials.findById(materialId)).thenReturn(Optional.of(material));
        when(materials.incrementCompletionAttempt(intentId)).thenReturn(true);
        when(materials.updateIntentState(intentId, UploadState.COMPLETING)).thenReturn(true);
        when(materials.transitionStatus(materialId, MaterialStatus.PENDING_UPLOAD, MaterialStatus.UPLOADED, NOW))
                .thenReturn(true);
        when(objectStorage.completeMultipart(eq(ObjectStoragePort.StorageZone.QUARANTINE), eq("upload-1"), eq(quarantineKey), anyList()))
                .thenReturn(new ObjectStoragePort.ObjectHead(quarantineKey, 16, "\"combined-etag\"", NOW));
        when(jobs.submit(eq(JobType.INGEST), eq("MATERIAL"), eq(materialId), anyMap(), eq(ACTOR_ID),
                eq(null), eq("trace"))).thenReturn(new JobSubmission(job, false));
        when(materials.completeIntent(intentId, jobId, "\"combined-etag\"", NOW)).thenReturn(true);

        JobSubmission submission = service.completeUpload(intentId,
                List.of(new MaterialService.UploadedPart(1, "\"etag1\""), new MaterialService.UploadedPart(2, "\"etag2\"")),
                ACTOR_ID, "trace");

        assertThat(submission.job().id()).isEqualTo(jobId);
        assertThat(submission.replayed()).isFalse();
        verify(objectStorage).completeMultipart(ObjectStoragePort.StorageZone.QUARANTINE, "upload-1", quarantineKey,
                List.of(new ObjectStoragePort.UploadedPart(1, "\"etag1\""),
                        new ObjectStoragePort.UploadedPart(2, "\"etag2\"")));
    }

    @Test
    void rejectsIncompleteMultipartCompletionBeforeCallingStorage() {
        UUID intentId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        String quarantineKey = "quarantine/" + materialId;
        Material material = new Material(materialId, "rules.pdf",
                com.knowledgemeltingpot.workbench.domain.MaterialFormat.PDF,
                "application/pdf", quarantineKey, "a".repeat(64), 16,
                MaterialStatus.PENDING_UPLOAD, NOW, NOW);
        MaterialUploadIntent intent = MaterialUploadIntent.multipart(intentId, materialId, ACTOR_ID, NOW,
                "upload-1", quarantineKey, 8L * 1024 * 1024, 2, NOW.plus(Duration.ofMinutes(15)));
        when(materials.lockIntent(intentId)).thenReturn(Optional.of(intent));
        when(materials.findById(materialId)).thenReturn(Optional.of(material));

        assertThatThrownBy(() -> service.completeUpload(intentId,
                List.of(new MaterialService.UploadedPart(2, "\"etag2\"")), ACTOR_ID, "trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("part count");
    }

    @Test
    void abortsMultipartUploadAndIntent() {
        UUID intentId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        String quarantineKey = "quarantine/" + materialId;
        MaterialUploadIntent intent = MaterialUploadIntent.multipart(intentId, materialId, ACTOR_ID, NOW,
                "upload-1", quarantineKey, 8L * 1024 * 1024, 1, NOW.plus(Duration.ofMinutes(15)));
        when(materials.lockIntent(intentId)).thenReturn(Optional.of(intent));
        when(materials.abortIntent(intentId, NOW)).thenReturn(true);

        service.abortUpload(intentId, ACTOR_ID, "trace");

        verify(objectStorage).abortMultipart(ObjectStoragePort.StorageZone.QUARANTINE, "upload-1", quarantineKey);
    }

    private MaterialUploadCommand command() {
        return new MaterialUploadCommand("rules.pdf", 16, "application/pdf", "a".repeat(64), round.id(),
                Set.of(), MaterialPartition.SOURCE, MaterialShareScope.ROUND, false);
    }
}
