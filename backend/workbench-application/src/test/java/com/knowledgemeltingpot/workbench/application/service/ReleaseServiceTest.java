package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.AgentExecutionAttemptRepository;
import com.knowledgemeltingpot.workbench.application.port.ReleaseItemSnapshot;
import com.knowledgemeltingpot.workbench.application.port.ReleaseRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetStatus;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.AgentExecutionAttempt;
import com.knowledgemeltingpot.workbench.domain.AgentExecutionAttemptStatus;
import com.knowledgemeltingpot.workbench.domain.AgentMountScope;
import com.knowledgemeltingpot.workbench.domain.AgentMountVersion;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.ModelConfigVersion;
import com.knowledgemeltingpot.workbench.domain.ModelConnection;
import com.knowledgemeltingpot.workbench.domain.ModelConnectionValidationStatus;
import com.knowledgemeltingpot.workbench.domain.ModelProvider;
import com.knowledgemeltingpot.workbench.domain.Release;
import com.knowledgemeltingpot.workbench.domain.ReleaseCoverage;
import com.knowledgemeltingpot.workbench.domain.ReleaseItemDisposition;
import com.knowledgemeltingpot.workbench.domain.ReleaseStatus;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReleaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    private SceneRepository scenes;
    private AssetRepository assets;
    private ReleaseRepository releases;
    private AuditService audit;
    private ObjectMapper objectMapper;
    private ReleaseService service;
    private AgentConfigurationService agentConfigurations;
    private ModelConnectionRepository models;
    private SkillRepository skills;
    private AgentExecutionAttemptRepository agentExecutions;

    @BeforeEach
    void setUp() {
        scenes = mock(SceneRepository.class);
        assets = mock(AssetRepository.class);
        releases = mock(ReleaseRepository.class);
        audit = mock(AuditService.class);
        agentConfigurations = mock(AgentConfigurationService.class);
        models = mock(ModelConnectionRepository.class);
        skills = mock(SkillRepository.class);
        agentExecutions = mock(AgentExecutionAttemptRepository.class);
        objectMapper = canonicalObjectMapper();
        when(releases.isFinalizedDocumentRevision(any(UUID.class), any(UUID.class))).thenReturn(true);
        service = new ReleaseService(scenes, assets, releases, audit, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), agentConfigurations,
                models, skills, agentExecutions);
    }

    @Test
    void selectedSubSceneRequiresAllFiveLatestReadyAssetsFromOneRevision() {
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        givenScene(sceneId, List.of(subSceneId));
        when(assets.findLatestByScene(sceneId)).thenReturn(readyAssets(subSceneId, revisionId, 3));
        when(releases.findLatestPublished(sceneId)).thenReturn(Optional.empty());

        ReleaseValidation validation = service.validate(sceneId,
                command(List.of(subSceneId), null));

        assertThat(validation.ready()).isTrue();
        assertThat(validation.coverage()).isEqualTo(ReleaseCoverage.FULL);
        assertThat(validation.selected()).containsExactly(subSceneId);
        assertThat(validation.carriedForward()).isEmpty();
        assertThat(validation.missing()).isEmpty();
    }

    @Test
    void selectedSubSceneIsBlockedWhenLatestAssetFailedOrRevisionsAreMixed() {
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID revisionA = UUID.randomUUID();
        UUID revisionB = UUID.randomUUID();
        givenScene(sceneId, List.of(subSceneId));
        List<Asset> latest = readyAssets(subSceneId, revisionA, 4);
        latest.set(0, asset(subSceneId, AssetType.RULE_CATALOG, 4, AssetStatus.FAILED, revisionA));
        latest.set(1, asset(subSceneId, AssetType.DECISION_FLOW, 4, AssetStatus.READY, revisionB));
        when(assets.findLatestByScene(sceneId)).thenReturn(latest);
        when(releases.findLatestPublished(sceneId)).thenReturn(Optional.empty());

        ReleaseValidation validation = service.validate(sceneId, command(List.of(subSceneId), null));

        assertThat(validation.ready()).isFalse();
        assertThat(validation.blockers()).anyMatch(value -> value.contains("not READY"));
        assertThatThrownBy(() -> service.publish(sceneId, command(List.of(subSceneId), null),
                UUID.randomUUID(), "trace-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("release preflight failed");
        verify(releases, never()).savePublished(any(), any());
    }

    @Test
    void selectedSubSceneIsBlockedWhenItsSharedRevisionIsNotFinalized() {
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        givenScene(sceneId, List.of(subSceneId));
        when(assets.findLatestByScene(sceneId)).thenReturn(readyAssets(subSceneId, revisionId, 2));
        when(releases.findLatestPublished(sceneId)).thenReturn(Optional.empty());
        when(releases.isFinalizedDocumentRevision(revisionId, subSceneId)).thenReturn(false);

        ReleaseValidation validation = service.validate(sceneId, command(List.of(subSceneId), null));

        assertThat(validation.ready()).isFalse();
        assertThat(validation.blockers()).containsExactly(
                "selected sub-scene document revision is not finalized: " + subSceneId + ":" + revisionId);
    }

    @Test
    void cumulativeReleaseCarriesPreviousSubSceneExactlyAndProducesReproducibleManifestHash() throws Exception {
        UUID sceneId = UUID.randomUUID();
        UUID selectedSubSceneId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID carriedSubSceneId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID selectedRevisionId = UUID.randomUUID();
        UUID carriedRevisionId = UUID.randomUUID();
        UUID baseReleaseId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        givenScene(sceneId, List.of(carriedSubSceneId, selectedSubSceneId));
        when(assets.findLatestByScene(sceneId)).thenReturn(readyAssets(selectedSubSceneId, selectedRevisionId, 7));
        Release previous = publishedRelease(baseReleaseId, sceneId, "v1.0.0", ReleaseCoverage.PARTIAL, null);
        when(releases.findLatestPublished(sceneId)).thenReturn(Optional.of(previous));
        List<ReleaseItemSnapshot> historicalItems = historicalItems(carriedSubSceneId, carriedRevisionId,
                baseReleaseId);
        when(releases.findItems(baseReleaseId)).thenReturn(historicalItems);
        when(releases.savePublished(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        Release release = service.publish(sceneId, command(List.of(selectedSubSceneId), baseReleaseId),
                actorId, "trace-2");

        assertThat(release.coverage()).isEqualTo(ReleaseCoverage.FULL);
        assertThat(release.previousReleaseId()).isEqualTo(baseReleaseId);
        ArgumentCaptor<List<ReleaseItemSnapshot>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(releases).savePublished(eq(release), itemCaptor.capture());
        List<ReleaseItemSnapshot> savedItems = itemCaptor.getValue();
        assertThat(savedItems).hasSize(10);
        assertThat(savedItems.stream().filter(item -> item.subSceneId().equals(carriedSubSceneId)))
                .allMatch(item -> item.disposition() == ReleaseItemDisposition.CARRIED_FORWARD
                        && baseReleaseId.equals(item.sourceReleaseId()))
                .extracting(ReleaseItemSnapshot::assetId)
                .containsExactlyInAnyOrderElementsOf(historicalItems.stream()
                        .map(ReleaseItemSnapshot::assetId).toList());

        ObjectNode manifest = (ObjectNode) objectMapper.readTree(release.manifestJson());
        assertThat(manifest.path("previousReleaseId").asText()).isEqualTo(baseReleaseId.toString());
        assertThat(manifest.path("note").asText()).isEqualTo("首次发布");
        assertThat(manifest.path("subScenes").get(0).path("status").asText()).isEqualTo("SELECTED");
        assertThat(manifest.path("subScenes").get(1).path("status").asText()).isEqualTo("CARRIED_FORWARD");
        assertThat(manifest.path("subScenes").get(1).path("sourceReleaseId").asText())
                .isEqualTo(baseReleaseId.toString());
        String embeddedHash = manifest.remove("sha256").asText();
        assertThat(embeddedHash).isEqualTo(release.manifestHash());
        assertThat(Hashes.sha256(objectMapper.writeValueAsString(manifest))).isEqualTo(embeddedHash);
    }

    @Test
    void firstPartialReleaseMarksNeverPublishedSubScenesMissing() throws Exception {
        UUID sceneId = UUID.randomUUID();
        UUID selectedSubSceneId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID missingSubSceneId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        givenScene(sceneId, List.of(missingSubSceneId, selectedSubSceneId));
        when(assets.findLatestByScene(sceneId)).thenReturn(readyAssets(selectedSubSceneId, UUID.randomUUID(), 1));
        when(releases.findLatestPublished(sceneId)).thenReturn(Optional.empty());
        when(releases.savePublished(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        Release release = service.publish(sceneId, command(List.of(selectedSubSceneId), null),
                UUID.randomUUID(), "trace-3");

        assertThat(release.coverage()).isEqualTo(ReleaseCoverage.PARTIAL);
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(release.manifestJson());
        assertThat(manifest.path("subScenes").get(1).path("status").asText()).isEqualTo("MISSING");
        assertThat(manifest.path("subScenes").get(1).path("assets")).isEmpty();
    }

    @Test
    void selectedSubSceneSnapshotsResolvedAgentLineageModelAndSkillWithoutCredentials() throws Exception {
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID modelConnectionId = UUID.randomUUID();
        UUID modelVersionId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        UUID skillVersionId = UUID.randomUUID();
        UUID templateVersionId = UUID.randomUUID();
        AgentMountVersion mount = new AgentMountVersion(UUID.randomUUID(), AgentRole.KNOWLEDGE_EXTRACTOR,
                AgentMountScope.SCENE, sceneId, 2, templateVersionId, true, modelVersionId, skillVersionId,
                "{\"strategy\":\"map-reduce\"}", "a".repeat(64), actorId, NOW);
        var effective = new AgentConfigurationService.EffectiveAgentConfiguration(
                AgentRole.KNOWLEDGE_EXTRACTOR, "知识萃取智能体", "EXTRACTION", true,
                modelVersionId, skillVersionId, mount.optionsJson(), "b".repeat(64), mount.id(),
                AgentMountScope.SCENE, AgentMountScope.SCENE, AgentMountScope.SCENE, "SCENE", List.of(mount));
        ModelConfigVersion model = new ModelConfigVersion(modelVersionId, modelConnectionId, 3, "qwen-plus",
                new BigDecimal("0.25"), 4096, actorId, NOW);
        ModelConnection connection = new ModelConnection(modelConnectionId, "DashScope", ModelProvider.DASHSCOPE,
                URI.create("https://dashscope.example.com/v1"), Optional.empty(), true,
                ModelConnectionValidationStatus.UNTESTED, null, actorId, NOW, NOW);
        Skill skill = new Skill(skillId, "受控萃取", SkillKind.TEMPLATE, "", null, null, null, actorId, NOW);
        SkillVersion skillVersion = new SkillVersion(skillVersionId, skillId, 4,
                "{\"executionMode\":\"RESOURCE_ONLY\"}", "c".repeat(64), actorId, NOW);
        givenScene(sceneId, List.of(subSceneId));
        when(assets.findLatestByScene(sceneId)).thenReturn(readyAssets(subSceneId, UUID.randomUUID(), 1));
        when(releases.findLatestPublished(sceneId)).thenReturn(Optional.empty());
        when(releases.savePublished(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentConfigurations.resolve(sceneId, subSceneId)).thenReturn(List.of(effective));
        when(models.findConfigVersion(modelVersionId)).thenReturn(Optional.of(model));
        when(models.findConnection(modelConnectionId)).thenReturn(Optional.of(connection));
        when(skills.findVersion(skillVersionId)).thenReturn(Optional.of(skillVersion));
        when(skills.findById(skillId)).thenReturn(Optional.of(skill));

        Release release = service.publish(sceneId, command(List.of(subSceneId), null), actorId, "trace-agent");

        var configuration = objectMapper.readTree(release.manifestJson())
                .path("subScenes").get(0).path("agentConfigurations").get(0);
        assertThat(configuration.path("role").asText()).isEqualTo("KNOWLEDGE_EXTRACTOR");
        assertThat(configuration.path("effectiveHash").asText()).isEqualTo("b".repeat(64));
        assertThat(configuration.path("model").path("provider").asText()).isEqualTo("DASHSCOPE");
        assertThat(configuration.path("model").path("modelId").asText()).isEqualTo("qwen-plus");
        assertThat(configuration.path("model").has("credential")).isFalse();
        assertThat(configuration.path("skill").path("packageHash").asText()).isEqualTo("c".repeat(64));
        assertThat(configuration.path("lineage").get(0).path("templateVersionId").asText())
                .isEqualTo(templateVersionId.toString());
    }

    @Test
    void manifestBindsEachNewAssetToItsActualFrozenAgentExecution() throws Exception {
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID modelVersionId = UUID.randomUUID();
        UUID skillVersionId = UUID.randomUUID();
        givenScene(sceneId, List.of(subSceneId));
        List<Asset> generated = readyAssets(subSceneId, revisionId, 1);
        when(assets.findLatestByScene(sceneId)).thenReturn(generated);
        when(releases.findLatestPublished(sceneId)).thenReturn(Optional.empty());
        when(releases.savePublished(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        for (Asset asset : generated) {
            when(agentExecutions.findByAsset(asset.id())).thenReturn(Optional.of(new AgentExecutionAttempt(
                    UUID.randomUUID(), UUID.randomUUID(), 1, AssetService.roleFor(asset.type()), asset.type(),
                    asset.id(), modelVersionId, skillVersionId, UUID.randomUUID(), "a".repeat(64),
                    "b".repeat(64), "c".repeat(64), AgentExecutionAttemptStatus.SUCCEEDED, "", NOW, NOW)));
        }

        Release release = service.publish(sceneId, command(List.of(subSceneId), null), UUID.randomUUID(),
                "trace-provenance");

        var manifest = objectMapper.readTree(release.manifestJson());
        assertThat(manifest.path("schemaVersion").asText()).isEqualTo("1.2");
        assertThat(manifest.path("subScenes").get(0).path("assets")).allSatisfy(entry -> {
            assertThat(entry.path("modelConfigVersionId").asText()).isEqualTo(modelVersionId.toString());
            assertThat(entry.path("skillVersionId").asText()).isEqualTo(skillVersionId.toString());
            assertThat(entry.path("effectiveConfigHash").asText()).isEqualTo("a".repeat(64));
            assertThat(entry.path("inputHash").asText()).isEqualTo("b".repeat(64));
            assertThat(entry.path("outputHash").asText()).isEqualTo("c".repeat(64));
            assertThat(entry.has("prompt")).isFalse();
            assertThat(entry.has("rawOutput")).isFalse();
        });
    }

    @Test
    void changedBaseReleaseFailsPreconditionAfterSceneLock() {
        UUID sceneId = UUID.randomUUID();
        UUID subSceneId = UUID.randomUUID();
        UUID expectedBase = UUID.randomUUID();
        UUID actualBase = UUID.randomUUID();
        givenScene(sceneId, List.of(subSceneId));
        when(releases.findLatestPublished(sceneId))
                .thenReturn(Optional.of(publishedRelease(actualBase, sceneId, "v1.1.0", ReleaseCoverage.FULL, null)));

        assertThatThrownBy(() -> service.publish(sceneId, command(List.of(subSceneId), expectedBase),
                UUID.randomUUID(), "trace-4"))
                .isInstanceOf(PreconditionFailedException.class)
                .hasMessageContaining("release baseline changed");
        verify(releases).lockScene(sceneId);
        verify(releases, never()).savePublished(any(), any());
    }

    @Test
    void explicitSecondaryConfirmationIsMandatory() {
        assertThatThrownBy(() -> new ReleaseCommand("v1.0.0", List.of(UUID.randomUUID()),
                "发布", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmation");
    }

    private void givenScene(UUID sceneId, List<UUID> subSceneIds) {
        when(scenes.findScene(sceneId)).thenReturn(Optional.of(new Scene(sceneId, "风控", "", NOW, NOW)));
        List<SubScene> subScenes = subSceneIds.stream()
                .map(id -> new SubScene(id, sceneId, "子场景-" + id, "", NOW, NOW))
                .toList();
        when(scenes.findSubScenes(sceneId)).thenReturn(subScenes);
    }

    private ReleaseCommand command(List<UUID> selectedSubSceneIds, UUID expectedBaseReleaseId) {
        return new ReleaseCommand("v2.0.0", selectedSubSceneIds, "首次发布", true, expectedBaseReleaseId);
    }

    private List<Asset> readyAssets(UUID subSceneId, UUID revisionId, int version) {
        List<Asset> result = new ArrayList<>();
        for (AssetType type : AssetType.values()) {
            result.add(asset(subSceneId, type, version, AssetStatus.READY, revisionId));
        }
        return result;
    }

    private Asset asset(UUID subSceneId, AssetType type, int version, AssetStatus status, UUID revisionId) {
        return new Asset(UUID.randomUUID(), subSceneId, type, version, status, revisionId,
                status == AssetStatus.READY ? "assets/" + type + ".json" : "",
                status == AssetStatus.READY ? "sha256-" + type : "", "", NOW, NOW);
    }

    private List<ReleaseItemSnapshot> historicalItems(UUID subSceneId, UUID revisionId, UUID sourceReleaseId) {
        return Arrays.stream(AssetType.values())
                .map(type -> new ReleaseItemSnapshot(UUID.randomUUID(), subSceneId, type, 2, revisionId,
                        "historical/" + type + ".json", "historical-sha256-" + type,
                        ReleaseItemDisposition.SELECTED, sourceReleaseId))
                .sorted(java.util.Comparator.comparing(item -> item.assetType().name()))
                .toList();
    }

    private Release publishedRelease(UUID releaseId, UUID sceneId, String tag, ReleaseCoverage coverage,
            UUID previousReleaseId) {
        return new Release(releaseId, sceneId, tag, ReleaseStatus.PUBLISHED, coverage, "历史发布",
                previousReleaseId, "{}", "0123456789abcdef", UUID.randomUUID(), NOW, NOW);
    }

    private ObjectMapper canonicalObjectMapper() {
        SimpleModule instantModule = new SimpleModule();
        instantModule.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeString(value.toString());
            }
        });
        return new ObjectMapper()
                .registerModule(instantModule)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }
}
