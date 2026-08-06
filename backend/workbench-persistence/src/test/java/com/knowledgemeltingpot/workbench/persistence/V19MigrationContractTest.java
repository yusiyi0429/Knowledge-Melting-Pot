package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V19MigrationContractTest {
    @Test
    void addsRecoverableExplorationArchivalMetadata() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V19__exploration_soft_deletion.sql")) {
            assertThat(stream).isNotNull();
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(migration)
                    .contains("deleted_at")
                    .contains("deleted_by")
                    .contains("where deleted_at is null");
        }
    }
}
