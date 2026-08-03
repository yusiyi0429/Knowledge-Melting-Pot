package com.knowledgemeltingpot.workbench.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserAccountSecurityTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void passwordReplacementClearsTheInitialPasswordFlagWithoutChangingIdentity() {
        UserAccount account = account(true);

        UserAccount changed = account.withPasswordHash("{bcrypt}replacement", false, CREATED_AT.plusSeconds(30));

        assertThat(changed.id()).isEqualTo(account.id());
        assertThat(changed.passwordHash()).isEqualTo("{bcrypt}replacement");
        assertThat(changed.mustChangePassword()).isFalse();
        assertThat(changed.roles()).containsExactly(UserRole.OPERATOR);
    }

    @Test
    void administrativeUpdateCannotMutatePasswordOrInitialPasswordFlag() {
        UserAccount account = account(true);

        UserAccount changed = account.withAdministration("Updated name", UserStatus.DISABLED,
                Set.of(UserRole.PUBLISHER), CREATED_AT.plusSeconds(30));

        assertThat(changed.passwordHash()).isEqualTo(account.passwordHash());
        assertThat(changed.mustChangePassword()).isTrue();
        assertThat(changed.status()).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void diagnosticStringRedactsPasswordHash() {
        UserAccount account = account(true);

        assertThat(account.toString()).contains("passwordHash=[REDACTED]").doesNotContain("{bcrypt}hash");
    }

    private UserAccount account(boolean mustChangePassword) {
        return new UserAccount(UUID.randomUUID(), "operator", "Operator", "{bcrypt}hash", UserStatus.ACTIVE,
                Set.of(UserRole.OPERATOR), mustChangePassword, CREATED_AT, CREATED_AT);
    }
}
