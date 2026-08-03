package com.knowledgemeltingpot.workbench.api.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

class SessionRevocationServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void revokesEverySessionForChangedAccount() {
        FindByIndexNameSessionRepository<Session> repository = mock(FindByIndexNameSessionRepository.class);
        when(repository.findByPrincipalName("operator"))
                .thenReturn(Map.of("session-a", mock(Session.class), "session-b", mock(Session.class)));

        new SessionRevocationService(repository).revokeAll("operator");

        verify(repository).deleteById("session-a");
        verify(repository).deleteById("session-b");
    }
}
