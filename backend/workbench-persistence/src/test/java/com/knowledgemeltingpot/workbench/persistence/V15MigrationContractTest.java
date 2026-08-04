package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V15MigrationContractTest {
    @Test
    void replacesOfflineValidationWithVerifiedConnectivityState() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V15__model_connectivity_validation.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("WHERE validation_status = 'CONFIGURATION_VALIDATED'",
                "SET validation_status = 'UNTESTED', last_validated_at = NULL",
                "CHECK (validation_status IN ('UNTESTED', 'CONNECTIVITY_VERIFIED'))",
                "validation_status <> 'CONNECTIVITY_VERIFIED'");
    }
}
