package com.knowledgemeltingpot.workbench.api.security;

import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;

@Component
public class JdbcSessionPersistenceGuard {
    public JdbcSessionPersistenceGuard(JdbcIndexedSessionRepository sessionRepository) {
        // Requiring the concrete repository is intentional: the API must fail closed
        // if Spring Session JDBC is ever removed or replaced by an in-memory store.
    }
}
