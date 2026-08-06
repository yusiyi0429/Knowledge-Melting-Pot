package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.ModelEndpointRuleService;
import com.knowledgemeltingpot.workbench.domain.ModelEndpointRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
@RequestMapping("/api/v1/model-endpoint-rules")
@PreAuthorize("hasRole('ADMIN')")
public class ModelEndpointRuleController {
    private final ModelEndpointRuleService service;
    private final CurrentUser currentUser;

    public ModelEndpointRuleController(ModelEndpointRuleService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ModelEndpointRuleResponse> list() {
        return service.list().stream().map(ModelEndpointRuleResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ModelEndpointRuleResponse> create(@Valid @RequestBody ModelEndpointRuleRequest body,
            Authentication authentication) {
        ModelEndpointRule rule = service.create(body.host(), body.allowedPorts(), body.allowHttp(),
                body.allowPrivateAddresses(), currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/model-endpoint-rules/" + rule.id()))
                .body(ModelEndpointRuleResponse.from(rule));
    }

    @PutMapping("/{ruleId}")
    public ModelEndpointRuleResponse update(@PathVariable UUID ruleId,
            @Valid @RequestBody ModelEndpointRuleRequest body, Authentication authentication) {
        return ModelEndpointRuleResponse.from(service.update(ruleId, body.host(), body.allowedPorts(),
                body.allowHttp(), body.allowPrivateAddresses(), currentUser.id(authentication),
                RequestIdFilter.currentTraceId()));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID ruleId, Authentication authentication) {
        service.delete(ruleId, currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.noContent().build();
    }

    public record ModelEndpointRuleRequest(
            @NotBlank @Size(max = 253) String host,
            @NotEmpty @Size(max = 32) Set<@Min(1) @Max(65_535) Integer> allowedPorts,
            boolean allowHttp,
            boolean allowPrivateAddresses) {
    }

    public record ModelEndpointRuleResponse(
            UUID id,
            String host,
            List<Integer> allowedPorts,
            boolean allowHttp,
            boolean allowPrivateAddresses,
            Instant createdAt,
            Instant updatedAt) {
        static ModelEndpointRuleResponse from(ModelEndpointRule rule) {
            return new ModelEndpointRuleResponse(rule.id(), rule.host(), rule.allowedPorts().stream().sorted().toList(),
                    rule.allowHttp(), rule.allowPrivateAddresses(), rule.createdAt(), rule.updatedAt());
        }
    }
}
