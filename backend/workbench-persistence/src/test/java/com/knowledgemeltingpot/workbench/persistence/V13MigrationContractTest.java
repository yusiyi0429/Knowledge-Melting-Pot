package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V13MigrationContractTest {
    @Test
    void explorationCandidatesAreImmutableAndNotificationsAreUserScoped() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V13__exploration_search_notifications.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("CREATE TABLE exploration_session", "CREATE TABLE exploration_material",
                "CREATE TABLE exploration_candidate", "CREATE TABLE exploration_candidate_material",
                "CREATE TABLE exploration_acceptance", "CREATE TABLE user_notification",
                "trg_exploration_candidate_immutable", "UNIQUE (user_id, notification_type, resource_type, resource_id)");
        assertThat(sql).contains("material_id UUID NOT NULL UNIQUE REFERENCES material(id) ON DELETE RESTRICT");
        assertThat(sql).doesNotContain("ON DELETE CASCADE,\n    material_id");
    }
}
