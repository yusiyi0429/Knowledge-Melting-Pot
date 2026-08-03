package com.knowledgemeltingpot.workbench.persistence;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcUserRepositoryTimeBindingTest {
    @Test
    void bindsAccountInstantsAsJdbc42TimeValues() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        Instant now = Instant.parse("2026-08-03T07:49:58.123456Z");
        UserAccount account = new UserAccount(UUID.randomUUID(), "admin", "Administrator", "hash",
                UserStatus.ACTIVE, Set.of(UserRole.ADMIN), true, now, now);

        new JdbcUserRepository(jdbc).save(account);

        verify(statement).param("createdAt", JdbcTimes.toJdbc(now));
        verify(statement).param("updatedAt", JdbcTimes.toJdbc(now));
    }
}
