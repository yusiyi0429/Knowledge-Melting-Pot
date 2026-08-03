package com.knowledgemeltingpot.workbench.domain;

import java.util.EnumSet;

public enum AssetStatus {
    PENDING,
    GENERATING,
    READY,
    FAILED,
    SUPERSEDED;

    public boolean canTransitionTo(AssetStatus target) {
        return switch (this) {
            case PENDING -> EnumSet.of(GENERATING, FAILED, SUPERSEDED).contains(target);
            case GENERATING -> EnumSet.of(READY, FAILED, SUPERSEDED).contains(target);
            case FAILED -> EnumSet.of(GENERATING, SUPERSEDED).contains(target);
            case READY -> target == SUPERSEDED;
            case SUPERSEDED -> false;
        };
    }
}
