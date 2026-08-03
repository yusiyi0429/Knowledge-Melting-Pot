package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.JobSubmission;
import com.knowledgemeltingpot.workbench.application.service.MaterialService;
import com.knowledgemeltingpot.workbench.application.service.MaterialUploadCommand;
import com.knowledgemeltingpot.workbench.application.service.MaterialUploadIntentResult;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
@RequestMapping("/api/v1/materials")
public class MaterialController {
    private static final List<String> SUPPORTED_FORMATS = List.of("pdf", "docx", "xlsx", "txt");

    private final MaterialService materialService;
    private final CurrentUser currentUser;

    public MaterialController(MaterialService materialService, CurrentUser currentUser) {
        this.materialService = materialService;
        this.currentUser = currentUser;
    }

    @PostMapping("/upload-intents")
    public ResponseEntity<UploadIntentResponse> createUploadIntent(@Valid @RequestBody CreateUploadIntentRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        MaterialUploadIntentResult result = materialService.createUploadIntent(new MaterialUploadCommand(
                body.fileName(), body.sizeBytes(), body.mediaType(), body.sha256(), body.roundId(),
                body.subSceneIds(), body.partition(), body.shareScope(), body.regulatorySource()),
                currentUser.id(authentication), idempotencyKey, RequestIdFilter.currentTraceId());
        UploadIntentResponse response = UploadIntentResponse.from(result);
        return ResponseEntity.created(URI.create("/api/v1/materials/" + result.material().id()))
                .header("X-Idempotent-Replay", Boolean.toString(result.replayed()))
                .body(response);
    }

    @PostMapping("/upload-intents/{intentId}/complete")
    public ResponseEntity<MaterialJobAcceptedResponse> completeUpload(@PathVariable UUID intentId,
            @Valid @RequestBody CompleteUploadRequest body, Authentication authentication) {
        JobSubmission submission = materialService.completeUpload(intentId, body.etag(),
                currentUser.id(authentication), RequestIdFilter.currentTraceId());
        URI status = URI.create("/api/v1/jobs/" + submission.job().id());
        return ResponseEntity.accepted()
                .location(status)
                .header("X-Idempotent-Replay", Boolean.toString(submission.replayed()))
                .body(new MaterialJobAcceptedResponse(submission.job().id(), submission.job().status().name(),
                        status.toString(), status + "/events"));
    }

    @GetMapping("/{materialId}")
    public MaterialResponse get(@PathVariable UUID materialId) {
        return MaterialResponse.from(materialService.get(materialId), materialService.bindings(materialId));
    }

    public record CreateUploadIntentRequest(
            @NotBlank @Size(max = 255) String fileName,
            @Positive long sizeBytes,
            @NotBlank @Size(max = 200) String mediaType,
            @NotBlank @Pattern(regexp = "[A-Fa-f0-9]{64}") String sha256,
            @NotNull UUID roundId,
            Set<@NotNull UUID> subSceneIds,
            @NotNull MaterialPartition partition,
            @NotNull MaterialShareScope shareScope,
            boolean regulatorySource) {
    }

    public record CompleteUploadRequest(@NotBlank @Size(max = 200) String etag) {
    }

    public record UploadIntentResponse(
            UUID id,
            UUID materialId,
            String objectKey,
            String materialStatus,
            String uploadMode,
            String capabilityStatus,
            boolean uploadUrlAvailable,
            long maxBytes,
            List<String> supportedFormats,
            String completionBehavior,
            String messageCode) {

        static UploadIntentResponse from(MaterialUploadIntentResult result) {
            return new UploadIntentResponse(result.intent().id(), result.material().id(),
                    result.material().objectKey(), result.material().status().name(), "DECLARATION_ONLY",
                    "OBJECT_STORAGE_NOT_CONFIGURED", false, Material.MAX_UPLOAD_BYTES, SUPPORTED_FORMATS,
                    "QUEUES_VALIDATION_ONLY", "material.upload.object-storage-not-configured");
        }
    }

    public record MaterialJobAcceptedResponse(
            UUID jobId,
            String status,
            String statusUrl,
            String eventsUrl) {
    }

    public record MaterialResponse(
            UUID id,
            String fileName,
            String format,
            String mediaType,
            String objectKey,
            String sha256,
            long sizeBytes,
            String status,
            List<BindingResponse> bindings) {

        static MaterialResponse from(Material material, List<RoundMaterial> bindings) {
            return new MaterialResponse(material.id(), material.fileName(), material.format().name(),
                    material.mediaType(), material.objectKey(), material.sha256(), material.sizeBytes(),
                    material.status().name(), bindings.stream().map(BindingResponse::from).toList());
        }
    }

    public record BindingResponse(
            UUID id,
            UUID roundId,
            UUID subSceneId,
            String partition,
            String shareScope,
            boolean regulatorySource,
            boolean active) {

        static BindingResponse from(RoundMaterial binding) {
            return new BindingResponse(binding.id(), binding.roundId(), binding.subSceneId(),
                    binding.partition().name(), binding.shareScope().name(), binding.regulatorySource(),
                    binding.active());
        }
    }
}
