package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V14MigrationContractTest {
    @Test
    void evaluationEvidenceIsReleaseBoundAndImmutable() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V14__release_evaluation_runtime.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("CREATE TABLE evaluation_run", "CREATE TABLE evaluation_case",
                "CREATE TABLE evaluation_case_result", "release_id UUID NOT NULL REFERENCES release_snapshot(id)",
                "model_config_version_id UUID NOT NULL REFERENCES model_config_version(id)",
                "skill_version_id UUID NOT NULL REFERENCES skill_version(id)",
                "trg_evaluation_run_input_immutable", "trg_evaluation_case_immutable",
                "trg_evaluation_result_immutable");
        assertThat(sql).contains("UNIQUE (evaluation_run_id, case_key)",
                "FOREIGN KEY (evaluation_run_id, case_id)",
                "status = 'SUCCEEDED' AND total_cases > 0 AND accuracy IS NOT NULL");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");
    }
}
