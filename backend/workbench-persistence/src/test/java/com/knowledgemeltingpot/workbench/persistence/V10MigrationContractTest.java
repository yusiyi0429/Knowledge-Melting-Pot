package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V10MigrationContractTest {

    @Test
    void versionTenAddsChunksProfilesAndEmbeddings() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V10__chunks_and_embeddings.sql")) {
            if (stream == null) {
                throw new IOException("V10 migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("create extension if not exists vector")
                .contains("drop constraint uk_material_sha256")
                .contains("create table material_chunk")
                .contains("blob_id")
                .contains("ordinal")
                .contains("source_ref_code")
                .contains("locator jsonb")
                .contains("content_hash")
                .contains("char_count")
                .contains("parser_version")
                .contains("uk_material_chunk")
                .contains("material_chunk is immutable")
                .contains("create table embedding_profile_version")
                .contains("provider")
                .contains("model_id")
                .contains("dimension")
                .contains("profile_version")
                .contains("uq_embedding_profile_active")
                .contains("create table chunk_embedding")
                .contains("chunk_id")
                .contains("profile_version_id")
                .contains("vector vector")
                .contains("primary key (chunk_id, profile_version_id)")
                .contains("vector_dims(vector) = dimension")
                .doesNotContain("hnsw")
                .doesNotContain("vector(3)")
                .doesNotContain("vector(1024)");
    }
}
