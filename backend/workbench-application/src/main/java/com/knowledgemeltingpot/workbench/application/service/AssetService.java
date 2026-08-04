package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelectionPort;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetStatus;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService {
    private static final Duration DOWNLOAD_EXPIRY = Duration.ofMinutes(5);

    private final AssetRepository assetRepository;
    private final SceneService sceneService;
    private final DocumentService documentService;
    private final JobService jobService;
    private final MaterialSelectionPort materialSelection;
    private final SceneRepository sceneRepository;
    private final Optional<ObjectStoragePort> objectStorage;
    private final Clock clock;

    public AssetService(AssetRepository assetRepository, SceneService sceneService, DocumentService documentService,
            JobService jobService, MaterialSelectionPort materialSelection, SceneRepository sceneRepository,
            Optional<ObjectStoragePort> objectStorage, Clock clock) {
        this.assetRepository = assetRepository;
        this.sceneService = sceneService;
        this.documentService = documentService;
        this.jobService = jobService;
        this.materialSelection = materialSelection;
        this.sceneRepository = sceneRepository;
        this.objectStorage = objectStorage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Asset> list(UUID subSceneId) {
        sceneService.getSubScene(subSceneId);
        return assetRepository.findLatestBySubScene(subSceneId);
    }

    @Transactional
    public JobSubmission requestGeneration(UUID subSceneId, UUID documentRevisionId,
            Set<AssetType> requestedTypes, UUID actorId, String idempotencyKey, String traceId) {
        sceneService.getSubScene(subSceneId);
        DocumentRevision revision = documentService.getRevision(documentRevisionId);
        if (!revision.subSceneId().equals(subSceneId)) {
            throw new IllegalArgumentException("document revision does not belong to the requested sub-scene");
        }
        if (!revision.finalized()) {
            throw new IllegalArgumentException(
                    "document revision must be finalized before generating assets");
        }
        Set<AssetType> types = requestedTypes == null || requestedTypes.isEmpty()
                ? Set.of(AssetType.values())
                : Set.copyOf(requestedTypes);
        JobType jobType = types.size() == AssetType.values().length ? JobType.GENERATE_ALL : JobType.GENERATE_ASSET;
        return jobService.submit(jobType, "SUB_SCENE", subSceneId,
                Map.of("assetTypes", types, "documentRevisionId", documentRevisionId),
                actorId, idempotencyKey, traceId);
    }

    @Transactional
    public Asset beginGeneration(UUID subSceneId, AssetType type, UUID documentRevisionId) {
        sceneService.getSubScene(subSceneId);
        return assetRepository.beginGeneration(subSceneId, type, documentRevisionId, Instant.now(clock));
    }

    @Transactional
    public Asset markReady(UUID assetId, String objectKey, String checksum) {
        return assetRepository.markReady(assetId, objectKey, checksum, Instant.now(clock));
    }

    @Transactional
    public Asset markFailed(UUID assetId, String failureReason) {
        return assetRepository.markFailed(assetId, failureReason, Instant.now(clock));
    }

    @Transactional
    public Asset markBlocked(UUID assetId, String reason) {
        return assetRepository.markBlocked(assetId, reason, Instant.now(clock));
    }

    /**
     * READY + active LABELED_HOLDOUT bindings for the sub-scene's latest round.
     * Only safe metadata (material + binding) is returned; content is never read.
     */
    @Transactional(readOnly = true)
    public List<MaterialSelection> holdoutSelection(UUID subSceneId) {
        SubScene subScene = sceneService.getSubScene(subSceneId);
        Optional<ExtractionRound> latestRound = sceneRepository.findRoundsByScene(subScene.sceneId()).stream()
                .filter(round -> round.subSceneId().equals(subSceneId))
                .max(Comparator.comparing(ExtractionRound::roundNumber));
        if (latestRound.isEmpty()) {
            return List.of();
        }
        return materialSelection.findForEvaluation(latestRound.get().id(), subSceneId);
    }

    @Transactional(readOnly = true)
    public URL downloadUrl(UUID assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("asset does not exist"));
        if (asset.status() != AssetStatus.READY) {
            throw new NotFoundException("asset is not ready for download");
        }
        return objectStorage.orElseThrow(() -> new IllegalStateException("object storage is not configured"))
                .presignDownload(ObjectStoragePort.StorageZone.ASSETS, asset.objectKey(), DOWNLOAD_EXPIRY);
    }
}
