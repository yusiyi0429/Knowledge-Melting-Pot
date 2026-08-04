package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.service.JobSubmission;
import com.knowledgemeltingpot.workbench.application.service.MaterialService;
import com.knowledgemeltingpot.workbench.application.service.MaterialUploadIntentResult;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class MaterialControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private final UUID actorId = UUID.randomUUID();
    private final Authentication authentication = mock(Authentication.class);
    private MaterialService service;
    private MaterialController controller;

    @BeforeEach
    void setUp() {
        service = mock(MaterialService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.id(authentication)).thenReturn(actorId);
        controller = new MaterialController(service, currentUser);
    }

    @Test
    void uploadIntentExplicitlyReportsMissingObjectStorageCapability() {
        UUID materialId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        Material material = new Material(materialId, "rules.pdf", MaterialFormat.PDF, "application/pdf",
                "quarantine/" + materialId, "a".repeat(64), 10, MaterialStatus.PENDING_UPLOAD, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), materialId, roundId, subSceneId,
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false, true, NOW);
        MaterialUploadIntent intent = MaterialUploadIntent.declarationOnly(intentId, materialId, actorId, NOW);
        when(service.createUploadIntent(any(), eq(actorId), eq("intent-key-001"), anyString()))
                .thenReturn(new MaterialUploadIntentResult(intent, material, List.of(binding), false));

        var response = controller.createUploadIntent(new MaterialController.CreateUploadIntentRequest(
                "rules.pdf", 10, "application/pdf", "a".repeat(64), roundId, Set.of(subSceneId),
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false), "intent-key-001", authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().uploadMode()).isEqualTo("DECLARATION_ONLY");
        assertThat(response.getBody().capabilityStatus()).isEqualTo("OBJECT_STORAGE_NOT_CONFIGURED");
        assertThat(response.getBody().uploadUrlAvailable()).isFalse();
        assertThat(response.getBody().completionBehavior()).isEqualTo("QUEUES_VALIDATION");
        assertThat(response.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");
    }

    @Test
    void completionReturnsOnlyDurableQueuedJobCoordinates() {
        UUID intentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, JobType.INGEST, "MATERIAL", UUID.randomUUID(), JobStatus.QUEUED, 0, 0,
                "{}", "", "", "", actorId, NOW, NOW);
        when(service.completeUpload(eq(intentId), any(), eq(actorId), anyString()))
                .thenReturn(new JobSubmission(job, true));

        var response = controller.completeUpload(intentId,
                new MaterialController.CompleteUploadRequest(List.of()), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().jobId()).isEqualTo(jobId);
        assertThat(response.getBody().status()).isEqualTo("QUEUED");
        assertThat(response.getBody().statusUrl()).isEqualTo("/api/v1/jobs/" + jobId);
        assertThat(response.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
    }

    @Test
    void workbenchListResponseIsSafeAndShowsNonReadyStatuses() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        Material scanning = new Material(materialId, "scan.pdf", MaterialFormat.PDF, "application/pdf",
                "quarantine/" + materialId, "a".repeat(64), 10, MaterialStatus.SCANNING, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), materialId, roundId, subSceneId,
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false, true, NOW);
        when(service.listWorkbenchMaterials(roundId, subSceneId))
                .thenReturn(List.of(new MaterialSelection(scanning, binding)));

        List<MaterialController.MaterialListItemResponse> items = controller.list(roundId, subSceneId);

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("SCANNING");
            assertThat(item.fileName()).isEqualTo("scan.pdf");
            assertThat(item.binding().partition()).isEqualTo("SOURCE");
            assertThat(item.binding().active()).isTrue();
        });
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(items);
        assertThat(json).contains("\"fileName\":\"scan.pdf\"", "\"status\":\"SCANNING\"")
                .doesNotContain("objectKey", "sha256", "quarantine/", "presign");
    }

    @Test
    void uploadIntentIncludesOrderedPartMetadataAndPartSize() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        Material material = new Material(materialId, "rules.pdf", MaterialFormat.PDF, "application/pdf",
                "quarantine/" + materialId, "a".repeat(64), 10, MaterialStatus.PENDING_UPLOAD, NOW, NOW);
        RoundMaterial binding = new RoundMaterial(UUID.randomUUID(), materialId, roundId, subSceneId,
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false, true, NOW);
        MaterialUploadIntent intent = MaterialUploadIntent.multipart(intentId, materialId, actorId, NOW,
                "upload-1", "quarantine/" + materialId, 5_242_880L, 2, NOW.plusSeconds(900));
        ObjectStoragePort.PresignedPart partOne = new ObjectStoragePort.PresignedPart(1,
                URI.create("https://minio.example/part/1").toURL(), Map.of("Content-Type", "application/pdf"));
        ObjectStoragePort.PresignedPart partTwo = new ObjectStoragePort.PresignedPart(2,
                URI.create("https://minio.example/part/2").toURL(), Map.of());
        MaterialUploadIntentResult result = new MaterialUploadIntentResult(intent, material, List.of(binding), false,
                true, "MULTIPART_PRESIGNED", "MULTIPART_PRESIGNED", "material.upload.multipart-presigned",
                List.of(partOne, partTwo));
        when(service.createUploadIntent(any(), eq(actorId), eq("intent-key-001"), anyString())).thenReturn(result);

        var response = controller.createUploadIntent(new MaterialController.CreateUploadIntentRequest(
                "rules.pdf", 10, "application/pdf", "a".repeat(64), roundId, Set.of(subSceneId),
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false), "intent-key-001", authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        MaterialController.UploadIntentResponse body = response.getBody();
        assertThat(body.partSize()).isEqualTo(5_242_880L);
        assertThat(body.partCount()).isEqualTo(2);
        assertThat(body.parts()).hasSize(2);
        assertThat(body.parts().get(0).partNumber()).isEqualTo(1);
        assertThat(body.parts().get(0).headers()).containsEntry("Content-Type", "application/pdf");
        assertThat(body.presignedUrls()).hasSize(2);
    }
}