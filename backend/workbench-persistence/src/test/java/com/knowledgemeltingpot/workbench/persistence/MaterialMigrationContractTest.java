package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MaterialMigrationContractTest {
    @Test
    void versionFiveSeparatesGlobalMaterialFromPartitionedBindings() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V5__material_foundation_and_holdout_isolation.sql")) {
            if (stream == null) {
                throw new IOException("V5 material migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("create table round_material")
                .contains("drop column round_id")
                .contains("labeled_holdout")
                .contains("size_bytes between 1 and 209715200")
                .contains("create table material_upload_intent")
                .contains("create trigger trg_material_metadata_immutable")
                .contains("material file metadata is immutable");
    }

    @Test
    void versionSevenAddsMultipartObjectStorageAndIngestSecurity() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V7__object_storage_and_ingest_security.sql")) {
            if (stream == null) {
                throw new IOException("V7 material migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("storage_upload_id")
                .contains("quarantine_object_key")
                .contains("upload_state")
                .contains("material_blob")
                .contains("material_ingest_attempt")
                .contains("prevent_material_blob_mutation")
                .contains("prevent_material_ingest_attempt_delete");
    }

    @Test
    void purposeSpecificQueriesPhysicallySeparateHoldout() {
        assertThat(JdbcMaterialRepository.KNOWLEDGE_PARTITIONS)
                .contains("SOURCE", "LABELED_TRAIN")
                .doesNotContain("LABELED_HOLDOUT");
        assertThat(JdbcMaterialRepository.HOLDOUT_PARTITION)
                .contains("LABELED_HOLDOUT")
                .doesNotContain("SOURCE", "LABELED_TRAIN");
        assertThat(JdbcMaterialRepository.REGULATORY_ONLY).contains("regulatory_source = TRUE");
    }
}
