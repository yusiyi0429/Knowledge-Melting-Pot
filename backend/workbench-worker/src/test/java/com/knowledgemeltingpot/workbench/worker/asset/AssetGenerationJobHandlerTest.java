package com.knowledgemeltingpot.workbench.worker.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.service.AssetService;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetStatus;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialFormat;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssetGenerationJobHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssetService assetService;
    private DocumentService documentService;
    private ObjectStoragePort storage;
    private AssetContentFactory factory;
    private AssetGenerationJobHandler handler;
    private UUID subSceneId;
    private DocumentRevision revision;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        documentService = mock(DocumentService.class);
        storage = mock(ObjectStoragePort.class);
        factory = new AssetContentFactory(objectMapper);
        handler = new AssetGenerationJobHandler(assetService, documentService, Optional.of(storage), factory,
                objectMapper);
        subSceneId = UUID.randomUUID();
        revision = new DocumentRevision(UUID.randomUUID(), subSceneId, subSceneId, 2, null,
                "# 逾期分类规则\n\n[SRC-001] 依据一\n\n[SRC-002] 依据二", "revision-hash-123", "note", true,
                ACTOR_ID, NOW, ACTOR_ID, NOW);
        when(documentService.getRevision(revision.id())).thenReturn(revision);
    }

    private LeasedJob job(Set<AssetType> types) throws Exception {
        Job domainJob = new Job(UUID.randomUUID(), JobType.GENERATE_ALL, "SUB_SCENE", subSceneId, JobStatus.QUEUED,
                0, 0, objectMapper.writeValueAsString(Map.of(
                        "assetTypes", types, "documentRevisionId", revision.id().toString())),
                "", "", "", ACTOR_ID, NOW, NOW);
        return new LeasedJob(domainJob, "worker", NOW.plus(Duration.ofMinutes(2)), 1);
    }

    private WorkerJobContext context(LeasedJob leasedJob) {
        return mock(WorkerJobContext.class);
    }

    private Asset asset(UUID id, AssetType type, int version) {
        return new Asset(id, subSceneId, type, version, AssetStatus.GENERATING, revision.id(), "", "", "", NOW, NOW);
    }

    @Test
    void generatesAllFiveAssetTypesIntoImmutableBundles() throws Exception {
        when(assetService.beginGeneration(eq(subSceneId), any(), eq(revision.id()))).thenAnswer(invocation -> {
            AssetType type = invocation.getArgument(1);
            return asset(UUID.randomUUID(), type, 1);
        });
        when(assetService.holdoutSelection(subSceneId)).thenReturn(List.of());
        ObjectStoragePort.ObjectHead head = new ObjectStoragePort.ObjectHead("assets/x", 10, "etag", NOW);
        when(storage.put(eq(ObjectStoragePort.StorageZone.ASSETS), anyString(), any(), eq("application/zip")))
                .thenReturn(head);

        JobHandlingResult result = handler.handle(job(Set.of(AssetType.values())), context(null));

        assertThat(result.succeeded()).isTrue();
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bundles = ArgumentCaptor.forClass(byte[].class);
        verify(storage, org.mockito.Mockito.times(4)).put(eq(ObjectStoragePort.StorageZone.ASSETS), keys.capture(),
                bundles.capture(), eq("application/zip"));
        assertThat(keys.getAllValues()).allMatch(key -> key.startsWith("assets/" + subSceneId + "/"));
        for (byte[] bundle : bundles.getAllValues()) {
            assertThat(bundle.length).isGreaterThan(0);
        }
        // EVALUATION_SET is blocked (no holdout), so four bundles were stored.
        verify(assetService).markBlocked(any(), org.mockito.ArgumentMatchers.contains("LABELED_HOLDOUT"));
    }

    @Test
    void ruleCatalogBundleContainsJsonAndXlsx() throws Exception {
        Asset asset = asset(UUID.randomUUID(), AssetType.RULE_CATALOG, 1);
        when(assetService.beginGeneration(subSceneId, AssetType.RULE_CATALOG, revision.id())).thenReturn(asset);
        when(storage.put(eq(ObjectStoragePort.StorageZone.ASSETS), anyString(), any(), eq("application/zip")))
                .thenReturn(new ObjectStoragePort.ObjectHead("assets/x", 10, "etag", NOW));

        handler.handle(job(Set.of(AssetType.RULE_CATALOG)), context(null));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bundle = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq(ObjectStoragePort.StorageZone.ASSETS), key.capture(), bundle.capture(),
                eq("application/zip"));
        assertThat(key.getValue()).contains("rule_catalog/v1-");
        Map<String, byte[]> files = unzip(bundle.getValue());
        assertThat(files).containsKeys("rules.json", "rules.xlsx");
        assertThat(new String(files.get("rules.json"), StandardCharsets.UTF_8)).contains("R001");
    }

    @Test
    void evaluationSetUsesOnlyHoldoutMetadataAndIsBlockedWithoutIt() throws Exception {
        when(assetService.beginGeneration(subSceneId, AssetType.EVALUATION_SET, revision.id()))
                .thenReturn(asset(UUID.randomUUID(), AssetType.EVALUATION_SET, 1));
        when(assetService.holdoutSelection(subSceneId)).thenReturn(List.of());

        JobHandlingResult result = handler.handle(job(Set.of(AssetType.EVALUATION_SET)), context(null));

        assertThat(result.succeeded()).isTrue();
        verify(assetService).markBlocked(any(), org.mockito.ArgumentMatchers.contains("no READY LABELED_HOLDOUT"));
        verify(storage, never()).put(any(), anyString(), any(), anyString());
    }

    @Test
    void evaluationSetWithHoldoutBuildsMetadataBundle() throws Exception {
        when(assetService.beginGeneration(subSceneId, AssetType.EVALUATION_SET, revision.id()))
                .thenReturn(asset(UUID.randomUUID(), AssetType.EVALUATION_SET, 1));
        Material material = new Material(UUID.randomUUID(), "holdout.xlsx", MaterialFormat.XLSX,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "verified-holdout/" + UUID.randomUUID(), "c".repeat(64), 10, MaterialStatus.READY, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), material.id(), UUID.randomUUID(), subSceneId,
                MaterialPartition.LABELED_HOLDOUT, MaterialShareScope.ROUND, false, true, NOW);
        when(assetService.holdoutSelection(subSceneId))
                .thenReturn(List.of(new MaterialSelection(material, binding)));
        when(storage.put(eq(ObjectStoragePort.StorageZone.ASSETS), anyString(), any(), eq("application/zip")))
                .thenReturn(new ObjectStoragePort.ObjectHead("assets/x", 10, "etag", NOW));

        JobHandlingResult result = handler.handle(job(Set.of(AssetType.EVALUATION_SET)), context(null));

        assertThat(result.succeeded()).isTrue();
        ArgumentCaptor<byte[]> bundle = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq(ObjectStoragePort.StorageZone.ASSETS), anyString(), bundle.capture(),
                eq("application/zip"));
        String evaluation = new String(unzip(bundle.getValue()).get("evaluation.json"), StandardCharsets.UTF_8);
        assertThat(evaluation).contains("holdoutCount").contains("LABELED_HOLDOUT")
                .doesNotContain("objectKey").doesNotContain("contentMd");
    }

    @Test
    void partialFailureMarksOnlyTheFailedAssetAndKeepsSuccessfulOnes() throws Exception {
        Asset qaAsset = asset(UUID.randomUUID(), AssetType.QA_PAIRS, 1);
        Asset ruleAsset = asset(UUID.randomUUID(), AssetType.RULE_CATALOG, 1);
        when(assetService.beginGeneration(subSceneId, AssetType.QA_PAIRS, revision.id())).thenReturn(qaAsset);
        when(assetService.beginGeneration(subSceneId, AssetType.RULE_CATALOG, revision.id())).thenReturn(ruleAsset);
        java.util.concurrent.atomic.AtomicBoolean firstPut = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(storage.put(eq(ObjectStoragePort.StorageZone.ASSETS), anyString(), any(), eq("application/zip")))
                .thenAnswer(invocation -> {
                    if (firstPut.getAndSet(false)) {
                        return new ObjectStoragePort.ObjectHead("assets/x", 10, "etag", NOW);
                    }
                    throw new IllegalStateException("object write failed");
                });

        JobHandlingResult result = handler.handle(job(Set.of(AssetType.RULE_CATALOG, AssetType.QA_PAIRS)),
                context(null));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("ASSET_GENERATION_PARTIAL");
        // The failed asset is marked FAILED; the successful one stays READY and is not rolled back.
        verify(assetService).markFailed(ruleAsset.id(), "object write failed");
        verify(assetService).markReady(eq(qaAsset.id()), anyString(), anyString());
    }

    @Test
    void rejectsUnfinalizedRevisionWithoutWritingAnything() throws Exception {
        DocumentRevision draft = new DocumentRevision(UUID.randomUUID(), subSceneId, subSceneId, 1, null,
                "# 标题\n[SRC-001]", "hash", "", false, null, null, ACTOR_ID, NOW);
        when(documentService.getRevision(draft.id())).thenReturn(draft);
        Job domainJob = new Job(UUID.randomUUID(), JobType.GENERATE_ASSET, "SUB_SCENE", subSceneId, JobStatus.QUEUED,
                0, 0, objectMapper.writeValueAsString(Map.of(
                        "assetTypes", Set.of(AssetType.QA_PAIRS), "documentRevisionId", draft.id().toString())),
                "", "", "", ACTOR_ID, NOW, NOW);

        JobHandlingResult result = handler.handle(new LeasedJob(domainJob, "worker", NOW.plus(Duration.ofMinutes(2)), 1),
                context(null));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DOCUMENT_NOT_FINALIZED");
        verify(assetService, never()).beginGeneration(any(), any(), any());
    }

    @Test
    void progressIsMonotonicAndReservesOneHundredForTheTerminalTransition() throws Exception {
        when(assetService.beginGeneration(eq(subSceneId), any(), eq(revision.id()))).thenAnswer(invocation -> {
            AssetType type = invocation.getArgument(1);
            return asset(UUID.randomUUID(), type, 1);
        });
        when(assetService.holdoutSelection(subSceneId)).thenReturn(List.of());
        when(storage.put(eq(ObjectStoragePort.StorageZone.ASSETS), anyString(), any(), eq("application/zip")))
                .thenReturn(new ObjectStoragePort.ObjectHead("assets/x", 10, "etag", NOW));
        WorkerJobContext context = mock(WorkerJobContext.class);

        handler.handle(job(Set.of(AssetType.values())), context);

        ArgumentCaptor<Integer> percents = ArgumentCaptor.forClass(Integer.class);
        verify(context, org.mockito.Mockito.atLeastOnce()).progress(percents.capture(), anyString());
        List<Integer> values = percents.getAllValues();
        assertThat(values).isSorted();
        assertThat(values.get(0)).as("first progress must not regress below the worker's reported value")
                .isGreaterThanOrEqualTo(1);
        assertThat(values.get(values.size() - 1)).isEqualTo(99);
        assertThat(values).doesNotContain(100);
    }

    @Test
    void stopsAtTheNextAssetBoundaryWhenCancellationIsRequested() throws Exception {
        WorkerJobContext context = mock(WorkerJobContext.class);
        when(context.cancellationRequested()).thenReturn(true);

        JobHandlingResult result = handler.handle(job(Set.of(AssetType.QA_PAIRS, AssetType.RULE_CATALOG)), context);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("ASSET_CANCELLED");
        verify(assetService, never()).beginGeneration(any(), any(), any());
    }

    private Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                zip.transferTo(output);
                files.put(entry.getName(), output.toByteArray());
            }
        }
        return files;
    }
}
