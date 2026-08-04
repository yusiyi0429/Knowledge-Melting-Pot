package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import java.util.Set;
import java.util.UUID;

public record MaterialUploadCommand(
        String fileName,
        long sizeBytes,
        String mediaType,
        String sha256,
        UUID roundId,
        Set<UUID> subSceneIds,
        MaterialPartition partition,
        MaterialShareScope shareScope,
        boolean regulatorySource,
        UUID explorationSessionId) {

    public MaterialUploadCommand {
        subSceneIds = subSceneIds == null ? Set.of() : Set.copyOf(subSceneIds);
    }

    public MaterialUploadCommand(String fileName, long sizeBytes, String mediaType, String sha256,
            UUID roundId, Set<UUID> subSceneIds, MaterialPartition partition,
            MaterialShareScope shareScope, boolean regulatorySource) {
        this(fileName, sizeBytes, mediaType, sha256, roundId, subSceneIds, partition,
                shareScope, regulatorySource, null);
    }
}
