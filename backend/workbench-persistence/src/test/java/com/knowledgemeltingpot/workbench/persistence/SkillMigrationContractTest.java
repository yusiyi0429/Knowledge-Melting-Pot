package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SkillMigrationContractTest {
    @Test
    void versionNineDefinesImmutableResourceOnlySkillsWithLineage() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/V9__skill_library.sql")) {
            if (stream == null) {
                throw new IOException("V9 skill migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("create table skill")
                .contains("create table skill_version")
                .contains("kind in ('template', 'instance')")
                .contains("source_skill_id")
                .contains("source_skill_version_id")
                .contains("package_hash")
                .contains("reject_skill_version_mutation")
                .contains("skill_version rows are immutable")
                .contains("trg_skill_immutable");
    }
}
