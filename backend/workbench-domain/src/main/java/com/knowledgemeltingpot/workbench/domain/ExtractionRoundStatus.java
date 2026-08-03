package com.knowledgemeltingpot.workbench.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ExtractionRoundStatus {
    DRAFT,
    PROCESSING,
    READY,
    FAILED,
    SUPERSEDED;

    public boolean canTransitionTo(ExtractionRoundStatus target) {
        Set<ExtractionRoundStatus> allowed = switch (this) {
            case DRAFT -> EnumSet.of(PROCESSING, SUPERSEDED);
            case PROCESSING -> EnumSet.of(READY, FAILED, SUPERSEDED);
            case FAILED -> EnumSet.of(PROCESSING, SUPERSEDED);
            case READY -> EnumSet.of(SUPERSEDED);
            case SUPERSEDED -> EnumSet.noneOf(ExtractionRoundStatus.class);
        };
        return allowed.contains(target);
    }
}
