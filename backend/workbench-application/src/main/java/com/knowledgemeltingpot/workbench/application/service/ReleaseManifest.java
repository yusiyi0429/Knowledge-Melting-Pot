package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.AgentMountScope;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import java.math.BigDecimal;
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
            List<AssetEntry> assets,
            List<AgentConfigurationEntry> agentConfigurations) {

        public SubSceneEntry {
            assets = List.copyOf(assets);
            agentConfigurations = List.copyOf(agentConfigurations);
        }
    }

    public record AssetEntry(
            UUID assetId,
            AssetType assetType,
            int assetVersion,
            UUID documentRevisionId,
            String objectKey,
            String checksum,
            @JsonInclude(JsonInclude.Include.NON_NULL) AgentRole agentRole,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID modelConfigVersionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID skillVersionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String effectiveConfigHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) String inputHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) String outputHash) {
    }

    public record AgentConfigurationEntry(
            AgentRole role,
            boolean enabled,
            String effectiveHash,
            UUID effectiveMountVersionId,
            ModelEntry model,
            SkillEntry skill,
            List<MountEntry> lineage) {
        public AgentConfigurationEntry { lineage = List.copyOf(lineage); }
    }

    public record ModelEntry(UUID configVersionId, UUID connectionId, ModelProvider provider, String modelId,
            int version, BigDecimal temperature, int maxOutputTokens) { }

    public record SkillEntry(UUID skillVersionId, UUID skillId, SkillKind kind, int version,
            String packageHash) { }

    public record MountEntry(UUID mountVersionId, AgentMountScope scope, int version,
            UUID templateVersionId, String configHash) { }
}
