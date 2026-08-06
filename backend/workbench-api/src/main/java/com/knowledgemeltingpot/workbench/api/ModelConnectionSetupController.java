package com.knowledgemeltingpot.workbench.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgemeltingpot.workbench.api.ModelConnectionController.ModelConfigVersionResponse;
import com.knowledgemeltingpot.workbench.api.ModelConnectionController.ModelConnectionResponse;
import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionTestResult;
import com.knowledgemeltingpot.workbench.application.service.ModelConnectionService;
import com.knowledgemeltingpot.workbench.application.service.ModelConnectionSetupService;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model-connection-setups")
@PreAuthorize("hasRole('ADMIN')")
public class ModelConnectionSetupController {
    private final ModelConnectionSetupService setups;
    private final ModelConnectionService modelConnections;
    private final CurrentUser currentUser;

    public ModelConnectionSetupController(ModelConnectionSetupService setups,
            ModelConnectionService modelConnections, CurrentUser currentUser) {
        this.setups = setups;
        this.modelConnections = modelConnections;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<ModelConnectionSetupResponse> configure(
            @Valid @RequestBody ModelConnectionSetupRequest body, Authentication authentication) {
        UUID actorId = currentUser.id(authentication);
        String traceId = RequestIdFilter.currentTraceId();
        char[] credential = credentialChars(body.credential());
        try {
            var configured = setups.configure(body.name(), body.provider(), body.baseUrl(), credential,
                    body.enabled(), body.modelId(), body.allowPrivateAddresses(), actorId, traceId);
            ModelConnectionTestResult testResult = modelConnections.test(
                    configured.connection().id(), actorId, traceId);
            var currentConnection = modelConnections.get(configured.connection().id());
            ModelConnectionSetupResponse response = new ModelConnectionSetupResponse(
                    ModelConnectionResponse.from(currentConnection),
                    ModelConfigVersionResponse.from(configured.configVersion()), testResult);
            return ResponseEntity.created(URI.create("/api/v1/model-connections/" + currentConnection.id()))
                    .body(response);
        } finally {
            wipe(credential);
        }
    }

    public record ModelConnectionSetupRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull ModelProvider provider,
            @NotBlank @Size(max = 2048) String baseUrl,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 8192) String credential,
            boolean enabled,
            @NotBlank @Size(max = 300) String modelId,
            boolean allowPrivateAddresses) {
        @Override
        public String toString() {
            return "ModelConnectionSetupRequest[name=" + name + ", provider=" + provider
                    + ", baseUrl=REDACTED, credential=REDACTED, enabled=" + enabled
                    + ", modelId=" + modelId + ", allowPrivateAddresses=" + allowPrivateAddresses + "]";
        }
    }

    public record ModelConnectionSetupResponse(
            ModelConnectionResponse connection,
            ModelConfigVersionResponse configVersion,
            ModelConnectionTestResult connectionTest) {
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
