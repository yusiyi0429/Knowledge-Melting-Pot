package com.knowledgemeltingpot.workbench.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestResult;
import com.knowledgemeltingpot.workbench.application.service.ModelConnectionService;
import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('ADMIN')")
public class ModelConnectionController {
    private final ModelConnectionService service;
    private final CurrentUser currentUser;

    public ModelConnectionController(ModelConnectionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/model-connections")
    public List<ModelConnectionResponse> list() {
        return service.list().stream().map(ModelConnectionResponse::from).toList();
    }

    @PostMapping("/model-connections")
    public ResponseEntity<ModelConnectionResponse> create(@Valid @RequestBody ModelConnectionRequest body,
            Authentication authentication) {
        char[] credential = credentialChars(body.credential());
        try {
            ModelConnection connection = service.create(body.name(), body.provider(), body.baseUrl(), credential,
                    body.enabled(), currentUser.id(authentication), RequestIdFilter.currentTraceId());
            return ResponseEntity.created(URI.create("/api/v1/model-connections/" + connection.id()))
                    .body(ModelConnectionResponse.from(connection));
        } finally {
            wipe(credential);
        }
    }

    @GetMapping("/model-connections/{connectionId}")
    public ModelConnectionResponse get(@PathVariable UUID connectionId) {
        return ModelConnectionResponse.from(service.get(connectionId));
    }

    @PutMapping("/model-connections/{connectionId}")
    public ModelConnectionResponse update(@PathVariable UUID connectionId,
            @Valid @RequestBody UpdateModelConnectionRequest body, Authentication authentication) {
        char[] credential = credentialChars(body.credential());
        try {
            return ModelConnectionResponse.from(service.update(connectionId, body.name(), body.provider(),
                    body.baseUrl(), credential, body.clearCredential(), body.enabled(),
                    currentUser.id(authentication), RequestIdFilter.currentTraceId()));
        } finally {
            wipe(credential);
        }
    }

    @DeleteMapping("/model-connections/{connectionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID connectionId, Authentication authentication) {
        service.delete(connectionId, currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/model-connections/{connectionId}/connection-tests")
    public ModelConnectionTestResult test(@PathVariable UUID connectionId, Authentication authentication) {
        return service.test(connectionId, currentUser.id(authentication), RequestIdFilter.currentTraceId());
    }

    @GetMapping("/model-connections/{connectionId}/config-versions")
    public List<ModelConfigVersionResponse> listVersions(@PathVariable UUID connectionId) {
        return service.listVersions(connectionId).stream().map(ModelConfigVersionResponse::from).toList();
    }

    @PostMapping("/model-connections/{connectionId}/config-versions")
    public ResponseEntity<ModelConfigVersionResponse> createVersion(@PathVariable UUID connectionId,
            @Valid @RequestBody CreateModelConfigVersionRequest body, Authentication authentication) {
        ModelConfigVersion version = service.createVersion(connectionId, body.modelId(), body.temperature(),
                body.maxOutputTokens(), currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/model-config-versions/" + version.id()))
                .body(ModelConfigVersionResponse.from(version));
    }

    @GetMapping("/model-config-versions/{versionId}")
    public ModelConfigVersionResponse getVersion(@PathVariable UUID versionId) {
        return ModelConfigVersionResponse.from(service.getVersion(versionId));
    }

    public record ModelConnectionRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull ModelProvider provider,
            @NotBlank @Size(max = 2048) String baseUrl,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 8192) String credential,
            boolean enabled) {
        @Override
        public String toString() {
            return "ModelConnectionRequest[name=" + name + ", provider=" + provider
                    + ", baseUrl=REDACTED, credential=REDACTED, enabled=" + enabled + "]";
        }
    }

    public record UpdateModelConnectionRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull ModelProvider provider,
            @NotBlank @Size(max = 2048) String baseUrl,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 8192) String credential,
            boolean clearCredential,
            boolean enabled) {
        @Override
        public String toString() {
            return "UpdateModelConnectionRequest[name=" + name + ", provider=" + provider
                    + ", baseUrl=REDACTED, credential=REDACTED, clearCredential=" + clearCredential
                    + ", enabled=" + enabled + "]";
        }
    }

    public record CreateModelConfigVersionRequest(
            @NotBlank @Size(max = 300) String modelId,
            @NotNull @DecimalMin("0.00") @DecimalMax("2.00") BigDecimal temperature,
            @Min(1) @Max(1_000_000) int maxOutputTokens) {
    }

    public record ModelConnectionResponse(
            UUID id,
            String name,
            ModelProvider provider,
            URI baseUrl,
            boolean enabled,
            boolean credentialConfigured,
            ModelConnectionValidationStatus validationStatus,
            Instant lastValidatedAt,
            Instant createdAt,
            Instant updatedAt) {
        public static ModelConnectionResponse from(ModelConnection connection) {
            return new ModelConnectionResponse(connection.id(), connection.name(), connection.provider(),
                    connection.baseUrl(), connection.enabled(), connection.credentialConfigured(),
                    connection.validationStatus(), connection.lastValidatedAt(), connection.createdAt(),
                    connection.updatedAt());
        }
    }

    public record ModelConfigVersionResponse(
            UUID id,
            UUID modelConnectionId,
            int version,
            String modelId,
            BigDecimal temperature,
            int maxOutputTokens,
            Instant createdAt) {
        public static ModelConfigVersionResponse from(ModelConfigVersion version) {
            return new ModelConfigVersionResponse(version.id(), version.modelConnectionId(), version.version(),
                    version.modelId(), version.temperature(), version.maxOutputTokens(), version.createdAt());
        }
    }

    private static char[] credentialChars(String credential) {
        return credential == null ? null : credential.toCharArray();
    }

    private static void wipe(char[] credential) {
        if (credential != null) {
            Arrays.fill(credential, '\0');
        }
    }
}
