package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V17MigrationContractTest {
    @Test
    void addsAdminManagedModelEndpointRulesAndAllowsHttpConnections() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V17__admin_model_endpoint_rules.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "DROP CONSTRAINT ck_model_connection_base_url_https",
                "CHECK (base_url LIKE 'https://%' OR base_url LIKE 'http://%')",
                "CREATE TABLE model_endpoint_rule",
                "allow_http BOOLEAN NOT NULL DEFAULT FALSE",
                "allow_private_addresses BOOLEAN NOT NULL DEFAULT FALSE",
                "CREATE UNIQUE INDEX uk_model_endpoint_rule_host");
    }
}
