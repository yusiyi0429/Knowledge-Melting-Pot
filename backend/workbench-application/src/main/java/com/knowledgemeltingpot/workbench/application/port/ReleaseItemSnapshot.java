package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.ReleaseItemDisposition;
import java.util.UUID;

public record ReleaseItemSnapshot(
        UUID assetId,
        UUID subSceneId,
        AssetType assetType,
        int assetVersion,
        UUID documentRevisionId,
        String objectKey,
        String checksum,
        ReleaseItemDisposition disposition,
        UUID sourceReleaseId) {
}
