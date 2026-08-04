package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.AssetService;
import com.knowledgemeltingpot.workbench.application.service.JobSubmission;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AssetController {
    private final AssetService assetService;
    private final CurrentUser currentUser;

    public AssetController(AssetService assetService, CurrentUser currentUser) {
        this.assetService = assetService;
        this.currentUser = currentUser;
    }

    @GetMapping("/subscenes/{subSceneId}/assets")
    public List<Asset> list(@PathVariable UUID subSceneId) {
        return assetService.list(subSceneId);
    }

    @GetMapping("/assets/{assetId}/download")
    public ResponseEntity<Void> download(@PathVariable UUID assetId) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(assetService.downloadUrl(assetId).toString()))
                .build();
    }

    @PostMapping({"/subscenes/{subSceneId}/asset-generation-jobs",
            "/subscenes/{subSceneId}/assets/generation-jobs"})
    public ResponseEntity<JobController.JobAcceptedResponse> generate(@PathVariable UUID subSceneId,
            @Valid @RequestBody GenerateAssetsRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        JobSubmission submission = assetService.requestGeneration(subSceneId, body.documentRevisionId(), body.types(),
                currentUser.id(authentication), idempotencyKey, RequestIdFilter.currentTraceId());
        URI status = URI.create("/api/v1/jobs/" + submission.job().id());
        return ResponseEntity.accepted()
                .location(status)
                .header("X-Idempotent-Replay", Boolean.toString(submission.replayed()))
                .body(new JobController.JobAcceptedResponse(submission.job().id(), status.toString(),
                        status + "/events", submission.job().status().name()));
    }

    public record GenerateAssetsRequest(
            @NotNull UUID documentRevisionId,
            @NotEmpty Set<AssetType> types) {
    }
}
