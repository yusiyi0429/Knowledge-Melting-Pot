package com.knowledgemeltingpot.workbench.application.service;

import java.util.List;

public record KnowledgeDiff(
        List<String> addedRuleIds,
        List<String> removedRuleIds,
        List<String> changedRuleIds,
        List<String> addedFlowIds,
        List<String> removedFlowIds,
        List<String> changedFlowIds,
        int sourceRefDelta) {
    public KnowledgeDiff {
        addedRuleIds = List.copyOf(addedRuleIds);
        removedRuleIds = List.copyOf(removedRuleIds);
        changedRuleIds = List.copyOf(changedRuleIds);
        addedFlowIds = List.copyOf(addedFlowIds);
        removedFlowIds = List.copyOf(removedFlowIds);
        changedFlowIds = List.copyOf(changedFlowIds);
    }
}
