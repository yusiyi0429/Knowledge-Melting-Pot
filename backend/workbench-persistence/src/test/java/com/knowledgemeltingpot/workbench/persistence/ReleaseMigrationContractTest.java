package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReleaseMigrationContractTest {
    @Test
    void versionFourAddsFinalizationAndCumulativeReleaseProvenance() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/V4__release_metadata.sql")) {
            if (stream == null) {
                throw new IOException("V4 migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("finalized_by uuid references app_user(id)")
                .contains("finalized_at timestamptz")
                .contains("previous_release_id uuid references release_snapshot(id)")
                .contains("source_release_id uuid references release_snapshot(id)")
                .contains("'selected', 'carried_forward'")
                .contains("alter column disposition drop default");
    }
}
