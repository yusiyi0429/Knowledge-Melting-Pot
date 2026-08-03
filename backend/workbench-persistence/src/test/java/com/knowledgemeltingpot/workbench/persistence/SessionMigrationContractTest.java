package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SessionMigrationContractTest {
    @Test
    void versionTwoOwnsUserSecurityColumnsAndJdbcSessionTables() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V2__persistent_sessions_and_user_security.sql")) {
            if (stream == null) {
                throw new IOException("V2 migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("must_change_password boolean not null default true")
                .contains("create table spring_session")
                .contains("create table spring_session_attributes")
                .contains("on delete cascade");
    }
}
