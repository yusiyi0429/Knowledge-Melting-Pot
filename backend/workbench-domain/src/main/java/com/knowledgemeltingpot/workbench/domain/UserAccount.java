package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String username,
        String displayName,
        String passwordHash,
        UserStatus status,
        Set<UserRole> roles,
        boolean mustChangePassword,
        Instant createdAt,
        Instant updatedAt) {

    public UserAccount {
        id = DomainChecks.required(id, "id");
        username = DomainChecks.text(username, "username");
        displayName = DomainChecks.text(displayName, "displayName");
        passwordHash = DomainChecks.text(passwordHash, "passwordHash");
        status = DomainChecks.required(status, "status");
        roles = Set.copyOf(DomainChecks.required(roles, "roles"));
        createdAt = DomainChecks.required(createdAt, "createdAt");
        updatedAt = DomainChecks.required(updatedAt, "updatedAt");
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
    }

    public UserAccount withPasswordHash(String newPasswordHash, boolean passwordChangeRequired, Instant changedAt) {
        return new UserAccount(id, username, displayName, newPasswordHash, status, roles,
                passwordChangeRequired, createdAt, changedAt);
    }

    public UserAccount withAdministration(String newDisplayName, UserStatus newStatus,
            Set<UserRole> newRoles, Instant changedAt) {
        return new UserAccount(id, username, newDisplayName, passwordHash, newStatus, newRoles,
                mustChangePassword, createdAt, changedAt);
    }

    @Override
    public String toString() {
        return "UserAccount[id=" + id + ", username=" + username + ", displayName=" + displayName
                + ", passwordHash=[REDACTED], status=" + status + ", roles=" + roles
                + ", mustChangePassword=" + mustChangePassword + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + "]";
    }
}
