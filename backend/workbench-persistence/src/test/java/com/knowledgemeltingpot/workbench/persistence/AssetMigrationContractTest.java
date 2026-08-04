package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AssetMigrationContractTest {
    @Test
    void versionEightAddsBlockedAssetStatusWithoutRewritingEarlierMigrations() throws IOException {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/V8__asset_blocked_status.sql")) {
            if (stream == null) {
                throw new IOException("V8 asset migration is missing");
            }
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(migration)
                .contains("alter table asset")
                .contains("drop constraint ck_asset_status")
                .contains("'blocked'")
                .contains("'pending'", "'generating'", "'ready'", "'failed'", "'superseded'");
    }
}
