package com.knowledgemeltingpot.workbench.domain;

import java.util.EnumSet;

public enum AssetStatus {
    PENDING,
    GENERATING,
    READY,
    FAILED,
    SUPERSEDED,
    BLOCKED;

    public boolean canTransitionTo(AssetStatus target) {
        return switch (this) {
            case PENDING -> EnumSet.of(GENERATING, FAILED, SUPERSEDED, BLOCKED).contains(target);
            case GENERATING -> EnumSet.of(READY, FAILED, SUPERSEDED, BLOCKED).contains(target);
            case BLOCKED -> EnumSet.of(GENERATING, SUPERSEDED).contains(target);
            case FAILED -> EnumSet.of(GENERATING, SUPERSEDED).contains(target);
            case READY -> target == SUPERSEDED;
            case SUPERSEDED -> false;
        };
    }
}
