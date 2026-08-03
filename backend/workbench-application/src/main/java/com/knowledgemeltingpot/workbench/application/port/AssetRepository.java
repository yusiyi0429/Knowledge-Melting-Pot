package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AssetRepository {
    void ensurePlaceholders(UUID subSceneId, Instant now);

    List<Asset> findLatestBySubScene(UUID subSceneId);

    List<Asset> findLatestByScene(UUID sceneId);

    List<Asset> findLatestReadyByScene(UUID sceneId);

    Asset saveNextVersion(Asset asset);

    Asset beginGeneration(UUID subSceneId, AssetType type, UUID documentRevisionId, Instant now);

    Asset markReady(UUID assetId, String objectKey, String checksum, Instant now);

    Asset markFailed(UUID assetId, String failureReason, Instant now);
}
