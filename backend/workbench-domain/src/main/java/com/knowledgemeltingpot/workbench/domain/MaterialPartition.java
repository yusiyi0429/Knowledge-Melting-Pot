package com.knowledgemeltingpot.workbench.domain;

public enum MaterialPartition {
    SOURCE,
    LABELED_TRAIN,
    LABELED_HOLDOUT;

    public boolean knowledgeVisible() {
        return this != LABELED_HOLDOUT;
    }

    public boolean evaluationVisible() {
        return this == LABELED_HOLDOUT;
    }
}
