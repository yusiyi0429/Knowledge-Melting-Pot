package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.api.security.SessionRevocationService;
import com.knowledgemeltingpot.workbench.application.service.AuditService;
import com.knowledgemeltingpot.workbench.application.service.UserAccountService;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private final UserAccountService userAccountService;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final SessionRevocationService sessionRevocationService;

    public UserController(UserAccountService userAccountService, CurrentUser currentUser, AuditService auditService,
            SessionRevocationService sessionRevocationService) {
        this.userAccountService = userAccountService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.sessionRevocationService = sessionRevocationService;
    }

    @GetMapping
    public List<UserResponse> list() {
        return userAccountService.listUsers().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest body,
            Authentication authentication) {
        UserAccount created = userAccountService.createUser(body.username(), body.displayName(),
                body.initialPassword(), body.roles());
        auditService.record(currentUser.id(authentication), "USER_CREATED", "USER", created.id(),
                auditDetails(created), RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id()))
                .body(UserResponse.from(created));
    }

    @PatchMapping("/{userId}")
    public UserResponse update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest body,
            Authentication authentication) {
        if (!body.hasChanges()) {
            throw new IllegalArgumentException("at least one user field must be supplied");
        }
        UUID actorId = currentUser.id(authentication);
        UserAccount updated = userAccountService.updateUser(actorId, userId, body.displayName(), body.enabled(),
                body.roles());
        auditService.record(actorId, "USER_UPDATED", "USER", updated.id(), auditDetails(updated),
                RequestIdFilter.currentTraceId());
        if (body.enabled() != null || body.roles() != null) {
            sessionRevocationService.revokeAll(updated.username());
        }
        return UserResponse.from(updated);
    }

    private Map<String, ?> auditDetails(UserAccount account) {
        return Map.of(
                "enabled", account.status() == UserStatus.ACTIVE,
                "roles", account.roles().stream().map(Enum::name).sorted().toList(),
                "mustChangePassword", account.mustChangePassword());
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,99}") String username,
            @NotBlank @Size(max = 200) String displayName,
            @NotBlank @Size(min = 12, max = 128) String initialPassword,
            @NotEmpty Set<@NotNull UserRole> roles) {
        @Override
        public String toString() {
            return "CreateUserRequest[username=" + username + ", displayName=" + displayName
                    + ", initialPassword=[REDACTED], roles=" + roles + "]";
        }
    }

    public record UpdateUserRequest(
            @Size(min = 1, max = 200) String displayName,
            Boolean enabled,
            @Size(min = 1) Set<@NotNull UserRole> roles) {

        boolean hasChanges() {
            return displayName != null || enabled != null || roles != null;
        }
    }
}
