package com.knowledgemeltingpot.workbench.domain;

import java.util.EnumSet;

public enum MaterialStatus {
    PENDING_UPLOAD,
    UPLOADED,
    SCANNING,
    READY,
    FAILED,
    INACTIVE;

    public boolean canTransitionTo(MaterialStatus target) {
        return switch (this) {
            case PENDING_UPLOAD -> EnumSet.of(UPLOADED, FAILED, INACTIVE).contains(target);
            case UPLOADED -> EnumSet.of(SCANNING, FAILED, INACTIVE).contains(target);
            case SCANNING -> EnumSet.of(READY, FAILED, INACTIVE).contains(target);
            case READY, FAILED -> target == INACTIVE;
            case INACTIVE -> false;
        };
    }
}
