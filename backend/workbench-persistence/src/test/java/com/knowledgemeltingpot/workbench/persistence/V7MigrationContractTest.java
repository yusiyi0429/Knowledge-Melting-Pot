package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V7MigrationContractTest {

    @Test
    void versionSevenAddsObjectStorageAndIngestSecurity() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V7__object_storage_and_ingest_security.sql")) {
            if (stream == null) {
                throw new IOException("V7 migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("alter table material_upload_intent")
                .contains("storage_upload_id")
                .contains("quarantine_object_key")
                .contains("upload_state")
                .contains("completion_attempt")
                .contains("initiated", "uploading", "completing", "completed", "aborted", "expired")
                .contains("create table material_blob")
                .contains("security_partition")
                .contains("verified_sha256")
                .contains("clean_object_key")
                .contains("uk_material_blob_content")
                .contains("create table material_ingest_attempt")
                .contains("material_id, attempt")
                .contains("head_verified", "malware_clean", "archive_budget_verified", "parsed", "object_verified")
                .contains("material_blob is immutable")
                .contains("material_ingest_attempt is append-only");
    }
}
