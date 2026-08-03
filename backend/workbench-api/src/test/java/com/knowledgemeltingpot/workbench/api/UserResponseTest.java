package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserResponseTest {
    @Test
    void publicUserShapeMatchesOpenApiAndNeverSerializesPasswordHash() throws Exception {
        UserAccount account = new UserAccount(UUID.randomUUID(), "publisher", "Publisher", "{bcrypt}secret-hash",
                UserStatus.ACTIVE, Set.of(UserRole.PUBLISHER, UserRole.OPERATOR), true,
                Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-03T00:00:00Z"));

        String json = new ObjectMapper().writeValueAsString(UserResponse.from(account));

        assertThat(json)
                .contains("\"username\":\"publisher\"")
                .contains("\"displayName\":\"Publisher\"")
                .contains("\"enabled\":true")
                .contains("\"mustChangePassword\":true")
                .doesNotContain("passwordHash", "secret-hash", "createdAt", "updatedAt");
    }

    @Test
    void createUserRequestDiagnosticStringRedactsInitialPassword() {
        var request = new UserController.CreateUserRequest(
                "operator", "Operator", "initial-password-value", Set.of(UserRole.OPERATOR));

        assertThat(request.toString()).contains("initialPassword=[REDACTED]").doesNotContain("initial-password-value");
    }
}
