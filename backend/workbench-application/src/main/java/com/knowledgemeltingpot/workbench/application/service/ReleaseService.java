package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.ReleaseItemSnapshot;
import com.knowledgemeltingpot.workbench.application.port.ReleaseRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetStatus;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.Release;
import com.knowledgemeltingpot.workbench.domain.ReleaseCoverage;
import com.knowledgemeltingpot.workbench.domain.ReleaseItemDisposition;
import com.knowledgemeltingpot.workbench.domain.ReleaseStatus;
import com.knowledgemeltingpot.workbench.domain.ReleaseSubSceneStatus;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseService {
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final Comparator<ReleaseItemSnapshot> ITEM_ORDER = Comparator
            .comparing(ReleaseItemSnapshot::subSceneId, UUID_ORDER)
            .thenComparing(item -> item.assetType().name());

    private final SceneRepository sceneRepository;
    private final AssetRepository assetRepository;
    private final ReleaseRepository releaseRepository;
    private final AuditService auditService;
    private final ObjectMapper canonicalObjectMapper;
    private final Clock clock;
    private final AgentConfigurationService agentConfigurationService;
    private final ModelConnectionRepository modelRepository;
    private final SkillRepository skillRepository;

    public ReleaseService(SceneRepository sceneRepository, AssetRepository assetRepository,
            ReleaseRepository releaseRepository, AuditService auditService, ObjectMapper objectMapper, Clock clock,
            AgentConfigurationService agentConfigurationService, ModelConnectionRepository modelRepository,
            SkillRepository skillRepository) {
        this.sceneRepository = sceneRepository;
        this.assetRepository = assetRepository;
        this.releaseRepository = releaseRepository;
        this.auditService = auditService;
        this.canonicalObjectMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
        this.agentConfigurationService = agentConfigurationService;
        this.modelRepository = modelRepository;
        this.skillRepository = skillRepository;
    }

    @Transactional(readOnly = true)
    public ReleaseValidation validate(UUID sceneId, ReleaseCommand command) {
        return buildPlan(sceneId, command, false).validation();
    }

    @Transactional
    public Release publish(UUID sceneId, ReleaseCommand command, UUID actorId, String traceId) {
        ReleasePlan plan = buildPlan(sceneId, command, true);
        if (!plan.validation().ready()) {
            throw new ConflictException("release preflight failed: "
                    + String.join("; ", plan.validation().blockers()));
        }

        Instant now = Instant.now(clock);
        UUID releaseId = UUID.randomUUID();
        UUID previousReleaseId = plan.validation().baseReleaseId();
        ReleaseManifest unsignedManifest = new ReleaseManifest("1.1", releaseId, sceneId, command.tag(),
                plan.validation().coverage(), command.note(), now, previousReleaseId, plan.subScenes(), "");
        String manifestHash = Hashes.sha256(toCanonicalJson(unsignedManifest));
        ReleaseManifest manifest = new ReleaseManifest("1.1", releaseId, sceneId, command.tag(),
                plan.validation().coverage(), command.note(), now, previousReleaseId, plan.subScenes(), manifestHash);
        String manifestJson = toCanonicalJson(manifest);
        Release release = new Release(releaseId, sceneId, command.tag(), ReleaseStatus.PUBLISHED,
                plan.validation().coverage(), command.note(), previousReleaseId, manifestJson, manifestHash,
                actorId, now, now);
        Release saved = releaseRepository.savePublished(release, plan.items());

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("sceneId", sceneId);
        auditDetails.put("tag", command.tag());
        auditDetails.put("coverage", plan.validation().coverage());
        auditDetails.put("selectedSubSceneIds", plan.validation().selected());
        auditDetails.put("manifestHash", manifestHash);
        if (previousReleaseId != null) {
            auditDetails.put("previousReleaseId", previousReleaseId);
        }
        auditService.record(actorId, "RELEASE_PUBLISHED", "RELEASE", releaseId, auditDetails, traceId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Release get(UUID releaseId) {
        return releaseRepository.find(releaseId)
                .orElseThrow(() -> new NotFoundException("release not found: " + releaseId));
    }

    /**
     * The current release baseline for a scene: the latest published release, if any.
     * Clients use this to set expectedBaseReleaseId so cumulative releases stay
     * optimistic-concurrency safe without weakening the server-side check.
     */
    @Transactional(readOnly = true)
    public Optional<Release> findLatestPublished(UUID sceneId) {
        sceneRepository.findScene(sceneId)
                .orElseThrow(() -> new NotFoundException("scene not found: " + sceneId));
        return releaseRepository.findLatestPublished(sceneId);
    }

    private ReleasePlan buildPlan(UUID sceneId, ReleaseCommand command, boolean lockScene) {
        sceneRepository.findScene(sceneId)
                .orElseThrow(() -> new NotFoundException("scene not found: " + sceneId));
        if (lockScene) {
            releaseRepository.lockScene(sceneId);
        }

        Release baseRelease = releaseRepository.findLatestPublished(sceneId).orElse(null);
        UUID baseReleaseId = baseRelease == null ? null : baseRelease.id();
        if (!Objects.equals(command.expectedBaseReleaseId(), baseReleaseId)) {
            throw new PreconditionFailedException("release baseline changed; expected "
                    + command.expectedBaseReleaseId() + " but latest is " + baseReleaseId);
        }

        List<SubScene> subScenes = sceneRepository.findSubScenes(sceneId).stream()
                .sorted(Comparator.comparing(SubScene::id, UUID_ORDER))
                .toList();
        Set<UUID> sceneSubSceneIds = subScenes.stream().map(SubScene::id).collect(java.util.stream.Collectors.toSet());
        Set<UUID> selectedIds = new HashSet<>(command.selectedSubSceneIds());
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        command.selectedSubSceneIds().stream()
                .filter(id -> !sceneSubSceneIds.contains(id))
                .sorted(UUID_ORDER)
                .forEach(id -> blockers.add("selected sub-scene does not belong to scene: " + id));
        if (subScenes.isEmpty()) {
            blockers.add("scene has no sub-scenes");
        }

        Map<String, Asset> latestAssets = indexAssets(assetRepository.findLatestByScene(sceneId));
        Map<UUID, List<ReleaseItemSnapshot>> previousItems = groupBySubScene(baseReleaseId == null
                ? List.of()
                : releaseRepository.findItems(baseReleaseId));
        Map<UUID, List<ReleaseManifest.AgentConfigurationEntry>> previousConfigurations =
                previousConfigurations(baseRelease);
        List<UUID> selected = new ArrayList<>();
        List<UUID> carriedForward = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        List<ReleaseItemSnapshot> items = new ArrayList<>();
        List<ReleaseManifest.SubSceneEntry> manifestSubScenes = new ArrayList<>();

        for (SubScene subScene : subScenes) {
            if (selectedIds.contains(subScene.id())) {
                selected.add(subScene.id());
                appendSelected(sceneId, subScene.id(), latestAssets, blockers, items, manifestSubScenes);
                continue;
            }

            List<ReleaseItemSnapshot> historicalItems = previousItems.getOrDefault(subScene.id(), List.of());
            if (!historicalItems.isEmpty()) {
                carriedForward.add(subScene.id());
                appendCarriedForward(subScene.id(), baseReleaseId, historicalItems, blockers, items,
                        manifestSubScenes, previousConfigurations.getOrDefault(subScene.id(), List.of()));
            } else {
                missing.add(subScene.id());
                manifestSubScenes.add(new ReleaseManifest.SubSceneEntry(subScene.id(),
                        ReleaseSubSceneStatus.MISSING, null, null, List.of(), List.of()));
            }
        }

        selected.sort(UUID_ORDER);
        carriedForward.sort(UUID_ORDER);
        missing.sort(UUID_ORDER);
        items.sort(ITEM_ORDER);
        manifestSubScenes.sort(Comparator.comparing(ReleaseManifest.SubSceneEntry::subSceneId, UUID_ORDER));
        ReleaseCoverage coverage = missing.isEmpty() ? ReleaseCoverage.FULL : ReleaseCoverage.PARTIAL;
        ReleaseValidation validation = new ReleaseValidation(blockers.isEmpty(), coverage, baseReleaseId,
                selected, carriedForward, missing, blockers, warnings);
        return new ReleasePlan(validation, List.copyOf(items), List.copyOf(manifestSubScenes));
    }

    private void appendSelected(UUID sceneId, UUID subSceneId, Map<String, Asset> latestAssets, List<String> blockers,
            List<ReleaseItemSnapshot> releaseItems, List<ReleaseManifest.SubSceneEntry> manifestSubScenes) {
        List<Asset> assets = new ArrayList<>();
        for (AssetType type : sortedAssetTypes()) {
            Asset asset = latestAssets.get(key(subSceneId, type));
            if (asset == null) {
                blockers.add("latest asset is missing: " + subSceneId + ":" + type);
            } else if (asset.status() != AssetStatus.READY) {
                blockers.add("latest asset is not READY: " + subSceneId + ":" + type
                        + " (" + asset.status() + ")");
            } else if (asset.documentRevisionId() == null) {
                blockers.add("READY asset has no document revision: " + subSceneId + ":" + type);
            } else {
                assets.add(asset);
            }
        }
        if (assets.size() != AssetType.values().length) {
            return;
        }
        Set<UUID> documentRevisionIds = assets.stream()
                .map(Asset::documentRevisionId)
                .collect(java.util.stream.Collectors.toSet());
        if (documentRevisionIds.size() != 1) {
            blockers.add("selected sub-scene assets do not share one document revision: " + subSceneId);
            return;
        }
        UUID documentRevisionId = documentRevisionIds.iterator().next();
        if (!releaseRepository.isFinalizedDocumentRevision(documentRevisionId, subSceneId)) {
            blockers.add("selected sub-scene document revision is not finalized: " + subSceneId
                    + ":" + documentRevisionId);
            return;
        }

        List<ReleaseItemSnapshot> selectedItems = assets.stream()
                .map(asset -> toSnapshot(asset, ReleaseItemDisposition.SELECTED, null))
                .sorted(ITEM_ORDER)
                .toList();
        releaseItems.addAll(selectedItems);
        List<ReleaseManifest.AgentConfigurationEntry> configurations = agentConfigurationService
                .resolve(sceneId, subSceneId).stream().map(this::snapshotConfiguration).toList();
        manifestSubScenes.add(toManifestSubScene(subSceneId, ReleaseSubSceneStatus.SELECTED, null, selectedItems,
                configurations));
    }

    private void appendCarriedForward(UUID subSceneId, UUID baseReleaseId,
            List<ReleaseItemSnapshot> historicalItems, List<String> blockers,
            List<ReleaseItemSnapshot> releaseItems, List<ReleaseManifest.SubSceneEntry> manifestSubScenes,
            List<ReleaseManifest.AgentConfigurationEntry> configurations) {
        List<ReleaseItemSnapshot> ordered = historicalItems.stream().sorted(ITEM_ORDER).toList();
        Set<AssetType> types = ordered.stream().map(ReleaseItemSnapshot::assetType)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(AssetType.class)));
        Set<UUID> documentRevisionIds = ordered.stream().map(ReleaseItemSnapshot::documentRevisionId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        boolean valid = ordered.size() == AssetType.values().length
                && types.size() == AssetType.values().length
                && documentRevisionIds.size() == 1
                && ordered.stream().noneMatch(item -> item.documentRevisionId() == null);
        if (!valid) {
            blockers.add("previous release contains an invalid sub-scene snapshot: " + subSceneId);
            return;
        }

        List<ReleaseItemSnapshot> carriedItems = ordered.stream()
                .map(item -> new ReleaseItemSnapshot(item.assetId(), item.subSceneId(), item.assetType(),
                        item.assetVersion(), item.documentRevisionId(), item.objectKey(), item.checksum(),
                        ReleaseItemDisposition.CARRIED_FORWARD, baseReleaseId))
                .toList();
        releaseItems.addAll(carriedItems);
        manifestSubScenes.add(toManifestSubScene(subSceneId, ReleaseSubSceneStatus.CARRIED_FORWARD,
                baseReleaseId, carriedItems, configurations));
    }

    private ReleaseManifest.SubSceneEntry toManifestSubScene(UUID subSceneId, ReleaseSubSceneStatus status,
            UUID sourceReleaseId, List<ReleaseItemSnapshot> items,
            List<ReleaseManifest.AgentConfigurationEntry> configurations) {
        List<ReleaseManifest.AssetEntry> assets = items.stream()
                .sorted(ITEM_ORDER)
                .map(item -> new ReleaseManifest.AssetEntry(item.assetId(), item.assetType(), item.assetVersion(),
                        item.documentRevisionId(), item.objectKey(), item.checksum()))
                .toList();
        UUID documentRevisionId = items.getFirst().documentRevisionId();
        return new ReleaseManifest.SubSceneEntry(subSceneId, status, sourceReleaseId, documentRevisionId, assets,
                configurations);
    }

    private ReleaseManifest.AgentConfigurationEntry snapshotConfiguration(
            AgentConfigurationService.EffectiveAgentConfiguration configuration) {
        ReleaseManifest.ModelEntry model = null;
        if (configuration.modelConfigVersionId() != null) {
            var version = modelRepository.findConfigVersion(configuration.modelConfigVersionId())
                    .orElseThrow(() -> new IllegalStateException("effective model version is unavailable"));
            var connection = modelRepository.findConnection(version.modelConnectionId())
                    .orElseThrow(() -> new IllegalStateException("effective model connection is unavailable"));
            model = new ReleaseManifest.ModelEntry(version.id(), connection.id(), connection.provider(),
                    version.modelId(), version.version(), version.temperature(), version.maxOutputTokens());
        }
        ReleaseManifest.SkillEntry skill = null;
        if (configuration.skillVersionId() != null) {
            var version = skillRepository.findVersion(configuration.skillVersionId())
                    .orElseThrow(() -> new IllegalStateException("effective Skill version is unavailable"));
            var definition = skillRepository.findById(version.skillId())
                    .orElseThrow(() -> new IllegalStateException("effective Skill is unavailable"));
            skill = new ReleaseManifest.SkillEntry(version.id(), definition.id(), definition.kind(),
                    version.version(), version.packageHash());
        }
        List<ReleaseManifest.MountEntry> lineage = configuration.lineage().stream()
                .map(mount -> new ReleaseManifest.MountEntry(mount.id(), mount.scope(), mount.version(),
                        mount.templateVersionId(), mount.configHash()))
                .toList();
        return new ReleaseManifest.AgentConfigurationEntry(configuration.role(), configuration.enabled(),
                configuration.effectiveHash(), configuration.effectiveMountVersionId(), model, skill, lineage);
    }

    private Map<UUID, List<ReleaseManifest.AgentConfigurationEntry>> previousConfigurations(Release baseRelease) {
        if (baseRelease == null || baseRelease.manifestJson() == null || baseRelease.manifestJson().isBlank()) {
            return Map.of();
        }
        try {
            ReleaseManifest manifest = canonicalObjectMapper.readValue(baseRelease.manifestJson(),
                    ReleaseManifest.class);
            Map<UUID, List<ReleaseManifest.AgentConfigurationEntry>> result = new HashMap<>();
            for (ReleaseManifest.SubSceneEntry entry : manifest.subScenes()) {
                result.put(entry.subSceneId(), entry.agentConfigurations());
            }
            return result;
        } catch (JsonProcessingException | NullPointerException exception) {
            return Map.of();
        }
    }

    private Map<String, Asset> indexAssets(List<Asset> assets) {
        Map<String, Asset> result = new HashMap<>();
        for (Asset asset : assets) {
            result.put(key(asset.subSceneId(), asset.type()), asset);
        }
        return result;
    }

    private Map<UUID, List<ReleaseItemSnapshot>> groupBySubScene(List<ReleaseItemSnapshot> items) {
        Map<UUID, List<ReleaseItemSnapshot>> result = new HashMap<>();
        for (ReleaseItemSnapshot item : items) {
            result.computeIfAbsent(item.subSceneId(), ignored -> new ArrayList<>()).add(item);
        }
        return result;
    }

    private List<AssetType> sortedAssetTypes() {
        return java.util.Arrays.stream(AssetType.values())
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    private String key(UUID subSceneId, AssetType type) {
        return subSceneId + ":" + type;
    }

    private ReleaseItemSnapshot toSnapshot(Asset asset, ReleaseItemDisposition disposition, UUID sourceReleaseId) {
        return new ReleaseItemSnapshot(asset.id(), asset.subSceneId(), asset.type(), asset.version(),
                asset.documentRevisionId(), asset.objectKey(), asset.checksum(), disposition, sourceReleaseId);
    }

    private String toCanonicalJson(Object value) {
        try {
            return canonicalObjectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("release manifest is not serializable", exception);
        }
    }

    private record ReleasePlan(
            ReleaseValidation validation,
            List<ReleaseItemSnapshot> items,
            List<ReleaseManifest.SubSceneEntry> subScenes) {
    }
}
