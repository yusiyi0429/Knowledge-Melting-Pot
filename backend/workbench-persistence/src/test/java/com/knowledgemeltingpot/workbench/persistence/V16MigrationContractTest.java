package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V16MigrationContractTest {
    @Test
    void bindsProfilesAndSeparatesMutableActivation() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V16__embedding_provider_and_dense_index.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql).contains("add column model_connection_id uuid",
                "create table embedding_profile_activation",
                "check (singleton_key = 1)",
                "drop index uq_embedding_profile_active")
                .doesNotContain("api_key", "credential_envelope");
    }
}
