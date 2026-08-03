package com.knowledgemeltingpot.workbench.application.service;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record ReleaseCommand(
        String tag,
        List<UUID> selectedSubSceneIds,
        String note,
        boolean confirmed,
        UUID expectedBaseReleaseId) {

    public ReleaseCommand {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("release tag is required");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("release note is required");
        }
        selectedSubSceneIds = selectedSubSceneIds == null ? List.of() : List.copyOf(selectedSubSceneIds);
        if (selectedSubSceneIds.isEmpty()) {
            throw new IllegalArgumentException("at least one sub-scene must be selected");
        }
        if (selectedSubSceneIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(selectedSubSceneIds).size() != selectedSubSceneIds.size()) {
            throw new IllegalArgumentException("selected sub-scenes must be unique non-null identifiers");
        }
        if (!confirmed) {
            throw new IllegalArgumentException("release confirmation is required");
        }
    }
}
