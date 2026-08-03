package com.knowledgemeltingpot.workbench.domain;

public enum ReleaseStatus {
    DRAFT,
    VALIDATING,
    PUBLISHED,
    FAILED;

    public boolean canTransitionTo(ReleaseStatus target) {
        return switch (this) {
            case DRAFT -> target == VALIDATING || target == FAILED;
            case VALIDATING -> target == PUBLISHED || target == FAILED;
            case PUBLISHED, FAILED -> false;
        };
    }
}
