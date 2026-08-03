package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.ReleaseCoverage;
import com.knowledgemeltingpot.workbench.domain.ReleaseSubSceneStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReleaseManifest(
        String schemaVersion,
        UUID releaseId,
        UUID sceneId,
        String tag,
        ReleaseCoverage coverage,
        String note,
        Instant generatedAt,
        UUID previousReleaseId,
        List<SubSceneEntry> subScenes,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String sha256) {

    public ReleaseManifest {
        subScenes = List.copyOf(subScenes);
    }

    public ReleaseManifest unsigned() {
        return new ReleaseManifest(schemaVersion, releaseId, sceneId, tag, coverage, note, generatedAt,
                previousReleaseId, subScenes, "");
    }

    public record SubSceneEntry(
            UUID subSceneId,
            ReleaseSubSceneStatus status,
            UUID sourceReleaseId,
            UUID documentRevisionId,
            List<AssetEntry> assets) {

        public SubSceneEntry {
            assets = List.copyOf(assets);
        }
    }

    public record AssetEntry(
            UUID assetId,
            AssetType assetType,
            int assetVersion,
            UUID documentRevisionId,
            String objectKey,
            String checksum) {
    }
}
