package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V12MigrationContractTest {
    @Test
    void governanceVersionsAndImportRecordsAreImmutableAndExtractionPinsTheRoleHash() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V12__agent_governance_configuration.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("CREATE TABLE agent_role_template_version", "CREATE TABLE agent_mount_version",
                "CREATE TABLE configuration_import", "CREATE TABLE configuration_import_application",
                "agent_mount_version_immutable", "configuration_import_immutable",
                "ADD COLUMN role_config_hash CHAR(64)", "FOREIGN KEY (role_config_version_id)");
        for (String role : new String[] {"SCENE_EXPLORER", "KNOWLEDGE_EXTRACTOR", "ALIGNMENT_REVIEWER",
                "RULE_CATALOG_GENERATOR", "DECISION_FLOW_GENERATOR", "SKILL_PACKAGER", "QA_EVALUATOR"}) {
            assertThat(sql).contains(role);
        }
    }
}
