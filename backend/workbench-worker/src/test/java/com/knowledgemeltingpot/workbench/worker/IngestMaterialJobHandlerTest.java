package com.knowledgemeltingpot.workbench.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.IngestCheckpointRepository;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.MaterialBlobRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialParserPort;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.port.VirusScanPort;
import com.knowledgemeltingpot.workbench.domain.IngestStage;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialBlob;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialIngestAttempt;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.SecurityPartition;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestMaterialJobHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String SHA256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
    private static final String OBJECT_KEY = "quarantine/" + MATERIAL_ID;

    @Mock
    private ObjectStoragePort objectStorage;
    @Mock
    private VirusScanPort virusScan;
    @Mock
    private MaterialParserPort parser;
    @Mock
    private MaterialRepository materialRepository;
    @Mock
    private MaterialBlobRepository blobRepository;
    @Mock
    private IngestCheckpointRepository checkpointRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private IngestMaterialJobHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new IngestMaterialJobHandler(objectStorage, virusScan, parser, materialRepository, blobRepository,
                checkpointRepository, objectMapper, clock);
        lenient().when(objectStorage.head(ObjectStoragePort.StorageZone.QUARANTINE, OBJECT_KEY))
                .thenReturn(new ObjectStoragePort.ObjectHead(OBJECT_KEY, 11, "\"etag\"", NOW));
        lenient().when(objectStorage.open(ObjectStoragePort.StorageZone.QUARANTINE, OBJECT_KEY))
                .thenAnswer(ignored -> new ByteArrayInputStream("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        lenient().when(virusScan.scan(any())).thenReturn(new VirusScanPort.ScanReport(true, "test-engine", "test-sig"));
        lenient().when(parser.detectMediaType(any())).thenReturn("text/plain");
        lenient().when(parser.parse(any(), eq(MaterialFormat.TXT)))
                .thenReturn(new MaterialParserPort.MaterialParseResult.Parsed("TxtParser", "1",
                        List.of(new MaterialParserPort.ParsedSegment(0,
                                new MaterialParserPort.SegmentLocator(MaterialParserPort.LocatorType.TXT_LINES,
                                        null, null, null, null, null, null, null, 1, 1),
                                "hello world"))));
        lenient().when(materialRepository.findBindings(MATERIAL_ID))
                .thenReturn(List.of(new RoundMaterial(UUID.randomUUID(), MATERIAL_ID, UUID.randomUUID(),
                        UUID.randomUUID(), MaterialPartition.SOURCE, MaterialShareScope.ROUND, false, true, NOW)));
        lenient().when(blobRepository.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(materialRepository.updateBlobId(eq(MATERIAL_ID), any(), eq(MaterialStatus.UPLOADED), eq(MaterialStatus.READY), eq(NOW)))
                .thenReturn(true);
    }

    @Test
    void happyPathParsesAndCommitsVerifiedBlob() {
        String payloadJson = toJson(Map.of(
                "intentId", UUID.randomUUID().toString(),
                "objectKey", OBJECT_KEY,
                "clientEtag", "\"etag\"",
                "expectedSha256", SHA256,
                "expectedSizeBytes", 11L,
                "format", "TXT"));
        LeasedJob leasedJob = new LeasedJob(
                new Job(JOB_ID, JobType.INGEST, "MATERIAL", MATERIAL_ID, JobStatus.RUNNING, 0, 1, payloadJson,
                        "", "", "", ACTOR_ID, NOW, NOW),
                "worker-1", NOW.plusSeconds(120), 1);
        Material material = new Material(MATERIAL_ID, "rules.txt", MaterialFormat.TXT, "text/plain", OBJECT_KEY,
                SHA256, 11, MaterialStatus.UPLOADED, NOW, NOW);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(objectStorage.head(eq(ObjectStoragePort.StorageZone.VERIFIED_KNOWLEDGE), any()))
                .thenReturn(new ObjectStoragePort.ObjectHead("knowledge/" + SHA256.substring(0, 2) + "/"
                        + SHA256.substring(2, 4) + "/" + SHA256 + ".txt", 11, "\"etag\"", NOW));
        when(objectStorage.open(eq(ObjectStoragePort.StorageZone.VERIFIED_KNOWLEDGE), any()))
                .thenAnswer(ignored -> new ByteArrayInputStream("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        WorkerJobContext context = mockContext();
        JobHandlingResult result = handler.handle(leasedJob, context);

        assertThat(result.succeeded()).isTrue();
        ArgumentCaptor<MaterialBlob> blob = ArgumentCaptor.forClass(MaterialBlob.class);
        verify(blobRepository).insert(blob.capture());
        assertThat(blob.getValue().securityPartition()).isEqualTo(SecurityPartition.KNOWLEDGE);
        verify(checkpointRepository).completeAttempt(
                JOB_ID, IngestStage.OBJECT_VERIFIED, "TxtParser", "1", NOW);
        // Running progress is capped at 99; the worker marks 100 on completion.
        verify(context).progress(75, "OBJECT_VERIFYING");
        verify(context, never()).progress(100, "COMPLETED");
    }

    @Test
    void holdoutBindingRoutesToVerifiedHoldoutPartition() {
        String payloadJson = toJson(Map.of(
                "intentId", UUID.randomUUID().toString(),
                "objectKey", OBJECT_KEY,
                "clientEtag", "\"etag\"",
                "expectedSha256", SHA256,
                "expectedSizeBytes", 11L,
                "format", "TXT"));
        LeasedJob leasedJob = new LeasedJob(
                new Job(JOB_ID, JobType.INGEST, "MATERIAL", MATERIAL_ID, JobStatus.RUNNING, 0, 1, payloadJson,
                        "", "", "", ACTOR_ID, NOW, NOW),
                "worker-1", NOW.plusSeconds(120), 1);
        Material material = new Material(MATERIAL_ID, "rules.txt", MaterialFormat.TXT, "text/plain", OBJECT_KEY,
                SHA256, 11, MaterialStatus.UPLOADED, NOW, NOW);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(materialRepository.findBindings(MATERIAL_ID))
                .thenReturn(List.of(new RoundMaterial(UUID.randomUUID(), MATERIAL_ID, UUID.randomUUID(),
                        UUID.randomUUID(), MaterialPartition.LABELED_HOLDOUT, MaterialShareScope.ROUND, false, true,
                        NOW)));
        when(objectStorage.head(eq(ObjectStoragePort.StorageZone.VERIFIED_HOLDOUT), any()))
                .thenReturn(new ObjectStoragePort.ObjectHead("holdout/" + SHA256 + ".txt", 11, "\"etag\"", NOW));
        when(objectStorage.open(eq(ObjectStoragePort.StorageZone.VERIFIED_HOLDOUT), any()))
                .thenAnswer(ignored -> new ByteArrayInputStream("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        JobHandlingResult result = handler.handle(leasedJob, mockContext());

        assertThat(result.succeeded()).isTrue();
        ArgumentCaptor<MaterialBlob> blob = ArgumentCaptor.forClass(MaterialBlob.class);
        verify(blobRepository).insert(blob.capture());
        assertThat(blob.getValue().securityPartition()).isEqualTo(SecurityPartition.HOLDOUT);
    }

    @Test
    void reClaimedJobDoesNotReinsertAttemptRow() {
        String payloadJson = toJson(Map.of(
                "intentId", UUID.randomUUID().toString(),
                "objectKey", OBJECT_KEY,
                "clientEtag", "\"etag\"",
                "expectedSha256", SHA256,
                "expectedSizeBytes", 11L,
                "format", "TXT"));
        LeasedJob leasedJob = new LeasedJob(
                new Job(JOB_ID, JobType.INGEST, "MATERIAL", MATERIAL_ID, JobStatus.RUNNING, 0, 1, payloadJson,
                        "", "", "", ACTOR_ID, NOW, NOW),
                "worker-1", NOW.plusSeconds(120), 1);
        Material material = new Material(MATERIAL_ID, "rules.txt", MaterialFormat.TXT, "text/plain", OBJECT_KEY,
                SHA256, 11, MaterialStatus.UPLOADED, NOW, NOW);
        MaterialIngestAttempt existing = new MaterialIngestAttempt(JOB_ID, MATERIAL_ID, 1, IngestStage.STARTED,
                null, false, NOW, null, null, null, null, null);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(checkpointRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(existing));
        when(objectStorage.head(eq(ObjectStoragePort.StorageZone.VERIFIED_KNOWLEDGE), any()))
                .thenReturn(new ObjectStoragePort.ObjectHead("knowledge/" + SHA256.substring(0, 2) + "/"
                        + SHA256.substring(2, 4) + "/" + SHA256 + ".txt", 11, "\"etag\"", NOW));
        when(objectStorage.open(eq(ObjectStoragePort.StorageZone.VERIFIED_KNOWLEDGE), any()))
                .thenAnswer(ignored -> new ByteArrayInputStream("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        JobHandlingResult result = handler.handle(leasedJob, mockContext());

        assertThat(result.succeeded()).isTrue();
        verify(checkpointRepository, never()).startAttempt(any());
        verify(checkpointRepository).reopenAttempt(JOB_ID, 1, NOW);
        verify(checkpointRepository).completeAttempt(JOB_ID, IngestStage.OBJECT_VERIFIED, "TxtParser", "1", NOW);
    }

    @Test
    void retryableFailureRecoversMaterialAndSucceedsOnSecondAttempt() {
        String payloadJson = toJson(Map.of(
                "intentId", UUID.randomUUID().toString(),
                "objectKey", OBJECT_KEY,
                "clientEtag", "\"etag\"",
                "expectedSha256", SHA256,
                "expectedSizeBytes", 11L,
                "format", "TXT"));
        LeasedJob leasedJob = new LeasedJob(
                new Job(JOB_ID, JobType.INGEST, "MATERIAL", MATERIAL_ID, JobStatus.RUNNING, 0, 2, payloadJson,
                        "", "", "", ACTOR_ID, NOW, NOW),
                "worker-1", NOW.plusSeconds(120), 2);
        // Material was failed by the first (retryable) attempt; manual retry must
        // bring it back to UPLOADED before re-running so updateBlobId(UPLOADED) works.
        Material material = new Material(MATERIAL_ID, "rules.txt", MaterialFormat.TXT, "text/plain", OBJECT_KEY,
                SHA256, 11, MaterialStatus.FAILED, NOW, NOW);
        MaterialIngestAttempt failed = new MaterialIngestAttempt(JOB_ID, MATERIAL_ID, 1, IngestStage.FAILED,
                "INGEST_EXCEPTION", true, NOW, NOW, null, null, null, null);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(checkpointRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(failed));
        when(materialRepository.transitionStatus(MATERIAL_ID, MaterialStatus.FAILED, MaterialStatus.UPLOADED, NOW))
                .thenReturn(true);
        when(objectStorage.head(eq(ObjectStoragePort.StorageZone.VERIFIED_KNOWLEDGE), any()))
                .thenReturn(new ObjectStoragePort.ObjectHead("knowledge/" + SHA256.substring(0, 2) + "/"
                        + SHA256.substring(2, 4) + "/" + SHA256 + ".txt", 11, "\"etag\"", NOW));
        when(objectStorage.open(eq(ObjectStoragePort.StorageZone.VERIFIED_KNOWLEDGE), any()))
                .thenAnswer(ignored -> new ByteArrayInputStream("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        JobHandlingResult result = handler.handle(leasedJob, mockContext());

        assertThat(result.succeeded()).isTrue();
        verify(materialRepository).transitionStatus(MATERIAL_ID, MaterialStatus.FAILED, MaterialStatus.UPLOADED, NOW);
        verify(checkpointRepository).reopenAttempt(JOB_ID, 2, NOW);
        verify(materialRepository).updateBlobId(eq(MATERIAL_ID), any(), eq(MaterialStatus.UPLOADED),
                eq(MaterialStatus.READY), eq(NOW));
    }

    @Test
    void nonRetryableFailureIsStablyRejectedOnRetry() {
        String payloadJson = toJson(Map.of(
                "intentId", UUID.randomUUID().toString(),
                "objectKey", OBJECT_KEY,
                "clientEtag", "\"etag\"",
                "expectedSha256", SHA256,
                "expectedSizeBytes", 11L,
                "format", "TXT"));
        LeasedJob leasedJob = new LeasedJob(
                new Job(JOB_ID, JobType.INGEST, "MATERIAL", MATERIAL_ID, JobStatus.RUNNING, 0, 2, payloadJson,
                        "", "", "", ACTOR_ID, NOW, NOW),
                "worker-1", NOW.plusSeconds(120), 2);
        Material material = new Material(MATERIAL_ID, "rules.txt", MaterialFormat.TXT, "text/plain", OBJECT_KEY,
                SHA256, 11, MaterialStatus.FAILED, NOW, NOW);
        MaterialIngestAttempt failed = new MaterialIngestAttempt(JOB_ID, MATERIAL_ID, 1, IngestStage.FAILED,
                "MALWARE_DETECTED", false, NOW, NOW, null, null, null, null);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(checkpointRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(failed));

        JobHandlingResult result = handler.handle(leasedJob, mockContext());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("RETRY_NOT_ALLOWED");
        verify(checkpointRepository, never()).reopenAttempt(any(), anyInt(), any());
        verify(materialRepository, never()).transitionStatus(MATERIAL_ID, MaterialStatus.FAILED,
                MaterialStatus.UPLOADED, NOW);
        verify(checkpointRepository).failAttempt(JOB_ID, IngestStage.STARTED, "RETRY_NOT_ALLOWED", false, NOW);
    }

    @Test
    void sameSha256BlobIsReusedWithoutReCopyOrReInsert() {
        String payloadJson = toJson(Map.of(
                "intentId", UUID.randomUUID().toString(),
                "objectKey", OBJECT_KEY,
                "clientEtag", "\"etag\"",
                "expectedSha256", SHA256,
                "expectedSizeBytes", 11L,
                "format", "TXT"));
        LeasedJob leasedJob = new LeasedJob(
                new Job(JOB_ID, JobType.INGEST, "MATERIAL", MATERIAL_ID, JobStatus.RUNNING, 0, 1, payloadJson,
                        "", "", "", ACTOR_ID, NOW, NOW),
                "worker-1", NOW.plusSeconds(120), 1);
        Material material = new Material(MATERIAL_ID, "rules.txt", MaterialFormat.TXT, "text/plain", OBJECT_KEY,
                SHA256, 11, MaterialStatus.UPLOADED, NOW, NOW);
        UUID existingBlobId = UUID.randomUUID();
        MaterialBlob existing = new MaterialBlob(existingBlobId, SecurityPartition.KNOWLEDGE, SHA256,
                "knowledge/" + SHA256.substring(0, 2) + "/" + SHA256.substring(2, 4) + "/" + SHA256 + ".txt",
                11, "text/plain", "test-engine", "test-sig", "TxtParser", "1", NOW);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(blobRepository.findByPartitionAndSha256(SecurityPartition.KNOWLEDGE, SHA256))
                .thenReturn(Optional.of(existing));

        JobHandlingResult result = handler.handle(leasedJob, mockContext());

        assertThat(result.succeeded()).isTrue();
        assertThat(result.resultReference()).isEqualTo(existingBlobId.toString());
        verify(objectStorage, never()).copyToVerified(any(), any(), any(), any());
        verify(blobRepository, never()).insert(any());
        verify(materialRepository).updateBlobId(MATERIAL_ID, existingBlobId, MaterialStatus.UPLOADED,
                MaterialStatus.READY, NOW);
    }

    @Test
    void readyMaterialReplaysSuccessfullyAfterLostLease() {
        String payloadJson = toJson(Map.of(
                "intentId", UUID.randomUUID().toString(),
                "objectKey", OBJECT_KEY,
                "clientEtag", "\"etag\"",
                "expectedSha256", SHA256,
                "expectedSizeBytes", 11L,
                "format", "TXT"));
        LeasedJob leasedJob = new LeasedJob(
                new Job(JOB_ID, JobType.INGEST, "MATERIAL", MATERIAL_ID, JobStatus.RUNNING, 0, 1, payloadJson,
                        "", "", "", ACTOR_ID, NOW, NOW),
                "worker-1", NOW.plusSeconds(120), 1);
        Material material = new Material(MATERIAL_ID, "rules.txt", MaterialFormat.TXT, "text/plain", OBJECT_KEY,
                SHA256, 11, MaterialStatus.READY, NOW, NOW);
        UUID existingBlobId = UUID.randomUUID();
        MaterialBlob existing = new MaterialBlob(existingBlobId, SecurityPartition.KNOWLEDGE, SHA256,
                "knowledge/" + SHA256.substring(0, 2) + "/" + SHA256.substring(2, 4) + "/" + SHA256 + ".txt",
                11, "text/plain", "test-engine", "test-sig", "TxtParser", "1", NOW);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(blobRepository.findByPartitionAndSha256(SecurityPartition.KNOWLEDGE, SHA256))
                .thenReturn(Optional.of(existing));

        JobHandlingResult result = handler.handle(leasedJob, mockContext());

        assertThat(result.succeeded()).isTrue();
        assertThat(result.resultReference()).isEqualTo(existingBlobId.toString());
        verify(objectStorage, never()).copyToVerified(any(), any(), any(), any());
        verify(materialRepository, never()).updateBlobId(any(), any(), any(), any(), any());
        verify(checkpointRepository).completeAttempt(JOB_ID, IngestStage.OBJECT_VERIFIED, "TxtParser", "1", NOW);
    }

    @Test
    void sizeMismatchFailsBeforeHash() {
        String payloadJson = toJson(Map.of(
                "intentId", UUID.randomUUID().toString(),
                "objectKey", OBJECT_KEY,
                "clientEtag", "\"etag\"",
                "expectedSha256", SHA256,
                "expectedSizeBytes", 999L,
                "format", "TXT"));
        LeasedJob leasedJob = new LeasedJob(
                new Job(JOB_ID, JobType.INGEST, "MATERIAL", MATERIAL_ID, JobStatus.RUNNING, 0, 1, payloadJson,
                        "", "", "", ACTOR_ID, NOW, NOW),
                "worker-1", NOW.plusSeconds(120), 1);
        Material material = new Material(MATERIAL_ID, "rules.txt", MaterialFormat.TXT, "text/plain", OBJECT_KEY,
                SHA256, 999, MaterialStatus.UPLOADED, NOW, NOW);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));

        JobHandlingResult result = handler.handle(leasedJob, mockContext());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("SIZE_MISMATCH");
    }

    private WorkerJobContext mockContext() {
        WorkerJobContext context = mock(WorkerJobContext.class);
        lenient().when(context.cancellationRequested()).thenReturn(false);
        return context;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
