package com.knowledgemeltingpot.workbench.application.port;

import java.util.List;
import java.util.UUID;

/**
 * Security boundary for regulatory alignment evidence. Implementations must reject
 * materials that are not explicitly marked regulatory or are in LABELED_HOLDOUT.
 */
public interface RegulatoryMaterialAccessPort {
    void requireRegulatoryNonHoldout(UUID documentId, List<UUID> materialIds);
}
