package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V20MigrationContractTest {
    @Test
    void storesOnlyRedactedAgentExecutionProvenance() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V20__agent_execution_attempt.sql")) {
            assertThat(stream).isNotNull();
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(migration)
                    .contains("agent_execution_attempt")
                    .contains("asset_id")
                    .contains("effective_config_hash")
                    .contains("input_hash")
                    .contains("output_hash")
                    .doesNotContain("prompt")
                    .doesNotContain("raw_output")
                    .doesNotContain("source_text");
        }
    }
}
