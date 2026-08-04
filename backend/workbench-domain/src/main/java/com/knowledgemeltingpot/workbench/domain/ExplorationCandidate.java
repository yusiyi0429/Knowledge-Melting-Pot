package com.knowledgemeltingpot.workbench.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExplorationCandidate(
        UUID id,
        UUID sessionId,
        int rank,
        String sceneName,
        String sceneDescription,
        String subSceneName,
        String subSceneDescription,
        String rationale,
        ValueLevel valueLevel,
        int estimatedRuleCount,
        int estimatedFlowCount,
        List<String> tags,
        List<UUID> materialIds,
        Instant createdAt) {

    public ExplorationCandidate {
        id = DomainChecks.required(id, "id");
        sessionId = DomainChecks.required(sessionId, "sessionId");
        sceneName = DomainChecks.text(sceneName, "sceneName");
        sceneDescription = DomainChecks.optionalText(sceneDescription);
        subSceneName = DomainChecks.text(subSceneName, "subSceneName");
        subSceneDescription = DomainChecks.optionalText(subSceneDescription);
        rationale = DomainChecks.text(rationale, "rationale");
        valueLevel = DomainChecks.required(valueLevel, "valueLevel");
        tags = tags == null ? List.of() : List.copyOf(tags);
        materialIds = materialIds == null ? List.of() : List.copyOf(materialIds);
        createdAt = DomainChecks.required(createdAt, "createdAt");
        if (rank < 1 || estimatedRuleCount < 0 || estimatedFlowCount < 0) {
            throw new IllegalArgumentException("candidate rank and estimates are invalid");
        }
    }

    public enum ValueLevel {
        HIGH,
        MEDIUM,
        LOW
    }
}
