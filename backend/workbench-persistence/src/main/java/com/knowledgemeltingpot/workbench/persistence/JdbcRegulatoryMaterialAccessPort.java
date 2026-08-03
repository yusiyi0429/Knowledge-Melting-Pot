package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.RegulatoryMaterialAccessPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegulatoryMaterialAccessPort implements RegulatoryMaterialAccessPort {
    private final JdbcClient jdbc;

    public JdbcRegulatoryMaterialAccessPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void requireRegulatoryNonHoldout(UUID documentId, List<UUID> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) {
            throw new IllegalArgumentException("regulatory material IDs are required");
        }
        Long eligibleCount = jdbc.sql("""
                SELECT COUNT(DISTINCT m.id)
                FROM material m
                JOIN round_material rm ON rm.material_id = m.id
                JOIN knowledge_document d ON d.sub_scene_id = rm.sub_scene_id
                WHERE d.id = :documentId
                  AND m.id IN (:materialIds)
                  AND m.status = 'READY'
                  AND rm.active = TRUE
                  AND rm.regulatory_source = TRUE
                  AND rm.partition IN ('SOURCE', 'LABELED_TRAIN')
                """)
                .param("documentId", documentId)
                .param("materialIds", materialIds)
                .query(Long.class)
                .single();
        if (eligibleCount == null || eligibleCount != materialIds.size()) {
            throw new IllegalArgumentException(
                    "every regulatory material must be active, READY, regulatory, non-HOLDOUT and visible to the document");
        }
    }
}
