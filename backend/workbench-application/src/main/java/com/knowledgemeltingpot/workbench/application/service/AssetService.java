package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.JobType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService {
    private final AssetRepository assetRepository;
    private final SceneService sceneService;
    private final DocumentService documentService;
    private final JobService jobService;
    private final Clock clock;

    public AssetService(AssetRepository assetRepository, SceneService sceneService, DocumentService documentService,
            JobService jobService, Clock clock) {
        this.assetRepository = assetRepository;
        this.sceneService = sceneService;
        this.documentService = documentService;
        this.jobService = jobService;
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
}
