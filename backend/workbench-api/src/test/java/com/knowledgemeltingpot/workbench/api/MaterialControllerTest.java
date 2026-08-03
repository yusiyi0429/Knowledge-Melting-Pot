package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
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
import java.time.Instant;
import java.util.List;
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
        MaterialUploadIntent intent = new MaterialUploadIntent(intentId, materialId, actorId, null, "", NOW, null);
        when(service.createUploadIntent(any(), eq(actorId), eq("intent-key-001"), anyString()))
                .thenReturn(new MaterialUploadIntentResult(intent, material, List.of(binding), false));

        var response = controller.createUploadIntent(new MaterialController.CreateUploadIntentRequest(
                "rules.pdf", 10, "application/pdf", "a".repeat(64), roundId, Set.of(subSceneId),
                MaterialPartition.SOURCE, MaterialShareScope.ROUND, false), "intent-key-001", authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().uploadMode()).isEqualTo("DECLARATION_ONLY");
        assertThat(response.getBody().capabilityStatus()).isEqualTo("OBJECT_STORAGE_NOT_CONFIGURED");
        assertThat(response.getBody().uploadUrlAvailable()).isFalse();
        assertThat(response.getBody().completionBehavior()).isEqualTo("QUEUES_VALIDATION_ONLY");
        assertThat(response.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("false");
    }

    @Test
    void completionReturnsOnlyDurableQueuedJobCoordinates() {
        UUID intentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, JobType.INGEST, "MATERIAL", UUID.randomUUID(), JobStatus.QUEUED, 0, 0,
                "{}", "", "", "", actorId, NOW, NOW);
        when(service.completeUpload(intentId, "etag", actorId, ""))
                .thenReturn(new JobSubmission(job, true));

        var response = controller.completeUpload(intentId,
                new MaterialController.CompleteUploadRequest("etag"), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().jobId()).isEqualTo(jobId);
        assertThat(response.getBody().status()).isEqualTo("QUEUED");
        assertThat(response.getBody().statusUrl()).isEqualTo("/api/v1/jobs/" + jobId);
        assertThat(response.getHeaders().getFirst("X-Idempotent-Replay")).isEqualTo("true");
    }
}
