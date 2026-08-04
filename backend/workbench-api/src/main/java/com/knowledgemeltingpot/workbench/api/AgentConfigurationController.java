package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.AgentConfigurationService;
import com.knowledgemeltingpot.workbench.application.service.AgentConfigurationService.AgentMountDraft;
import com.knowledgemeltingpot.workbench.application.service.AgentConfigurationService.EffectiveAgentConfiguration;
import com.knowledgemeltingpot.workbench.application.service.AgentConfigurationService.RoleDefinition;
import com.knowledgemeltingpot.workbench.application.service.AgentConfigurationService.ScopeConfiguration;
import com.knowledgemeltingpot.workbench.domain.AgentMountScope;
import com.knowledgemeltingpot.workbench.domain.ConfigurationImportPreview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
public class AgentConfigurationController {
    private final AgentConfigurationService service;
    private final CurrentUser currentUser;

    public AgentConfigurationController(AgentConfigurationService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/agent-roles")
    public List<RoleDefinition> roles() {
        return service.roles();
    }

    @GetMapping("/agent-mounts")
    public ResponseEntity<ScopeConfiguration> scope(@RequestParam AgentMountScope scope,
            @RequestParam(required = false) UUID scopeId) {
        ScopeConfiguration result = service.getScope(scope, scopeId);
        return ResponseEntity.ok().eTag(result.etag()).body(result);
    }

    @GetMapping("/agent-mounts/effective")
    public List<EffectiveAgentConfiguration> effective(@RequestParam UUID sceneId,
            @RequestParam(required = false) UUID subSceneId) {
        return service.resolve(sceneId, subSceneId);
    }

    @PostMapping("/agent-mounts/versions")
    public ResponseEntity<ScopeConfiguration> append(@Valid @RequestBody MountVersionRequest body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        requireGlobalAdmin(body.scope(), authentication);
        ScopeConfiguration result = service.append(body.scope(), body.scopeId(), body.toDraft(), ifMatch,
                currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.ok().eTag(result.etag()).body(result);
    }

    @GetMapping("/agent-configuration-catalog")
    public AgentConfigurationService.AgentConfigurationCatalog catalog() {
        return service.catalog();
    }

    @PostMapping("/configuration-imports/previews")
    public ResponseEntity<ConfigurationImportPreview> preview(@Valid @RequestBody ImportPreviewRequest body,
            Authentication authentication) {
        requireGlobalAdmin(body.scope(), authentication);
        ConfigurationImportPreview preview = service.previewImport(body.scope(), body.scopeId(),
                body.roles().stream().map(MountVersionRequest.RoleDraftRequest::toDraft).toList(),
                currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.ok().eTag(preview.manifestHash()).body(preview);
    }

    @GetMapping("/configuration-imports/{importId}")
    public ResponseEntity<ConfigurationImportPreview> getImport(@PathVariable UUID importId) {
        ConfigurationImportPreview preview = service.getImport(importId);
        return ResponseEntity.ok().eTag(preview.manifestHash()).body(preview);
    }

    @PostMapping("/configuration-imports/{importId}/apply")
    public ResponseEntity<ScopeConfiguration> apply(@PathVariable UUID importId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        ConfigurationImportPreview preview = service.getImport(importId);
        requireGlobalAdmin(preview.scope(), authentication);
        ScopeConfiguration result = service.applyImport(importId, ifMatch, currentUser.id(authentication),
                RequestIdFilter.currentTraceId());
        return ResponseEntity.ok().eTag(result.etag()).body(result);
    }

    private void requireGlobalAdmin(AgentMountScope scope, Authentication authentication) {
        if (scope == AgentMountScope.GLOBAL && !currentUser.isAdmin(authentication)) {
            throw new AccessDeniedException("GLOBAL Agent configuration requires ADMIN");
        }
    }

    public record MountVersionRequest(
            @NotNull AgentMountScope scope,
            UUID scopeId,
            @NotNull com.knowledgemeltingpot.workbench.domain.AgentRole role,
            Boolean enabled,
            UUID modelConfigVersionId,
            UUID skillVersionId,
            @Size(max = 64) Map<String, Object> options) {

        AgentMountDraft toDraft() {
            return new AgentMountDraft(role, enabled, modelConfigVersionId, skillVersionId, options);
        }

        public record RoleDraftRequest(
                @NotNull com.knowledgemeltingpot.workbench.domain.AgentRole role,
                Boolean enabled,
                UUID modelConfigVersionId,
                UUID skillVersionId,
                @Size(max = 64) Map<String, Object> options) {
            AgentMountDraft toDraft() {
                return new AgentMountDraft(role, enabled, modelConfigVersionId, skillVersionId, options);
            }
        }
    }

    public record ImportPreviewRequest(
            @NotNull AgentMountScope scope,
            UUID scopeId,
            @Valid @NotEmpty @Size(max = 7) List<MountVersionRequest.RoleDraftRequest> roles) {
    }
}
