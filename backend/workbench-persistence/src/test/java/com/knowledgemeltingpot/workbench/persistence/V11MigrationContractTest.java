package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V11MigrationContractTest {
    @Test
    void versionElevenPersistsFrozenRunsCheckpointsAndImmutableProjections() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V11__knowledge_ir_and_extraction_runs.sql")) {
            if (stream == null) throw new IOException("V11 migration is missing");
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(migration)
                .contains("create table extraction_run")
                .contains("canonical_input_hash")
                .contains("create table extraction_run_material")
                .contains("partition in ('source', 'labeled_train')")
                .contains("create table extraction_run_chunk")
                .contains("create table extraction_map_result")
                .contains("primary key (run_id, chunk_id)")
                .contains("create table extraction_reduce_result")
                .contains("create table document_revision_projection")
                .contains("create table document_revision_source_ref")
                .contains("reject_knowledge_terminal_mutation");
    }
}
