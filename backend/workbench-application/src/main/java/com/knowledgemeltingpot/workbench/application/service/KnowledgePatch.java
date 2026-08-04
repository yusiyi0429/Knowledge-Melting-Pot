package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;

public record KnowledgePatch(String operation, KnowledgeIr replacement, KnowledgeDiff diff) {
    public static final String REPLACE_OPERATION = "replaceKnowledgeIr";

    public KnowledgePatch {
        if (!REPLACE_OPERATION.equals(operation) || replacement == null || diff == null) {
            throw new IllegalArgumentException("a replaceKnowledgeIr patch with replacement and diff is required");
        }
    }
}
