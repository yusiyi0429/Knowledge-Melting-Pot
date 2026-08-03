package com.knowledgemeltingpot.workbench.domain;

import java.util.EnumSet;

public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(JobStatus target) {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING, CANCELLED).contains(target);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED).contains(target);
            case FAILED -> target == QUEUED;
            case SUCCEEDED, CANCELLED -> false;
        };
    }
}
