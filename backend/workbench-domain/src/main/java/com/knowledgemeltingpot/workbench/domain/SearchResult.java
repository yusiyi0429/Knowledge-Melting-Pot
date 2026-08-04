package com.knowledgemeltingpot.workbench.domain;

import java.util.UUID;

public record SearchResult(
        Type type,
        UUID sceneId,
        UUID subSceneId,
        UUID resourceId,
        String title,
        String excerpt) {

    public SearchResult {
        type = DomainChecks.required(type, "type");
        sceneId = DomainChecks.required(sceneId, "sceneId");
        resourceId = DomainChecks.required(resourceId, "resourceId");
        title = DomainChecks.text(title, "title");
        excerpt = DomainChecks.optionalText(excerpt);
    }

    public enum Type {
        SCENE,
        RULE,
        SOURCE
    }
}
