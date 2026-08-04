package com.knowledgemeltingpot.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the share-scope-aware material selection SQL: the port
 * queries must scope by round/sub-scene/share-scope and never let a caller
 * pass an arbitrary partition.
 */
class MaterialSelectionSqlContractTest {

    @Test
    void selectionSqlHonorsShareScopeAndWorkflowPartitions() throws Exception {
        String sql = constant("SELECTION_COLUMNS");
        String lower = sql.toLowerCase();

        assertThat(lower)
                .contains("rm.sub_scene_id in")
                .contains("sub_scene")
                .contains("rm.share_scope = 'round' and rm.round_id = :roundid")
                .contains("rm.share_scope = 'subscene' and rm.sub_scene_id = :subsceneid")
                .contains("rm.share_scope = 'scene'")
                .contains("m.status = 'ready'")
                .contains("rm.active = true");

        assertThat(constant("KNOWLEDGE_PARTITIONS"))
                .contains("rm.partition IN ('SOURCE', 'LABELED_TRAIN')");
        assertThat(constant("REGULATORY_ONLY"))
                .contains("rm.regulatory_source = TRUE");
        assertThat(constant("HOLDOUT_PARTITION"))
                .contains("rm.partition = 'LABELED_HOLDOUT'");
        assertThat(constant("SELECTION_ORDER")).startsWith(" ORDER BY");
        assertThat((sql + constant("KNOWLEDGE_PARTITIONS") + constant("SELECTION_ORDER")).toLowerCase())
                .containsSubsequence("rm.partition in", "order by");
    }

    private static String constant(String name) throws Exception {
        Field field = JdbcMaterialRepository.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
