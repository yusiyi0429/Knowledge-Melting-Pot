package com.knowledgemeltingpot.workbench.api.security;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

@Component
public class SessionRevocationService {
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public SessionRevocationService(FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public void revokeAll(String username) {
        sessionRepository.findByPrincipalName(username).keySet().forEach(sessionRepository::deleteById);
    }
}
