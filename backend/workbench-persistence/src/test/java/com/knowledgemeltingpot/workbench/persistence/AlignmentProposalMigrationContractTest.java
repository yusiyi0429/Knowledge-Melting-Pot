package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AlignmentProposalMigrationContractTest {
    @Test
    void versionSixKeepsProposalAndAdoptionRowsImmutable() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V6__immutable_alignment_proposals.sql")) {
            if (stream == null) {
                throw new IOException("V6 migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("create table alignment_proposal")
                .contains("base_revision_id uuid not null references document_revision(id) on delete restrict")
                .contains("structured_patch jsonb not null")
                .contains("source_refs jsonb not null")
                .contains("create table alignment_proposal_adoption")
                .contains("before update or delete on alignment_proposal")
                .contains("before update or delete on alignment_proposal_adoption");
    }
}
