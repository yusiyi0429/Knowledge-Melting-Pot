package com.knowledgemeltingpot.workbench.worker.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.service.AssetService;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.worker.JobHandler;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic asset generation for GENERATE_ASSET and GENERATE_ALL jobs.
 * Each asset is generated independently: a failure marks only that asset FAILED
 * and never rolls back assets already marked READY; a BLOCKED EVALUATION_SET
 * (no READY LABELED_HOLDOUT binding) is reported as BLOCKED with a safety
 * warning rather than pretending readiness.
 */
@Component
public class AssetGenerationJobHandler implements JobHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssetGenerationJobHandler.class);

    private final AssetService assetService;
    private final DocumentService documentService;
    private final Optional<ObjectStoragePort> objectStorage;
    private final AssetContentFactory contentFactory;
    private final ObjectMapper objectMapper;

    public AssetGenerationJobHandler(AssetService assetService, DocumentService documentService,
            Optional<ObjectStoragePort> objectStorage, AssetContentFactory contentFactory,
            ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.documentService = documentService;
        this.objectStorage = objectStorage;
        this.contentFactory = contentFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.GENERATE_ASSET || type == JobType.GENERATE_ALL;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public JobHandlingResult handle(LeasedJob leasedJob, WorkerJobContext context) throws Exception {
        UUID jobId = leasedJob.job().id();
        UUID subSceneId = leasedJob.job().aggregateId();
        Payload payload;
        try {
            payload = objectMapper.readValue(leasedJob.job().payloadJson(), Payload.class);
        } catch (IOException exception) {
            return JobHandlingResult.failure("ASSET_PAYLOAD_INVALID", "asset generation payload is invalid");
        }
        if (payload.assetTypes() == null || payload.assetTypes().isEmpty() || payload.documentRevisionId() == null) {
            return JobHandlingResult.failure("ASSET_PAYLOAD_INVALID", "asset generation payload is incomplete");
        }
        DocumentRevision revision;
        try {
            revision = documentService.getRevision(payload.documentRevisionId());
        } catch (RuntimeException exception) {
            return JobHandlingResult.failure("DOCUMENT_REVISION_NOT_FOUND", exception.getMessage());
        }
        if (!revision.subSceneId().equals(subSceneId)) {
            return JobHandlingResult.failure("ASSET_SUB_SCENE_MISMATCH",
                    "document revision does not belong to the job sub-scene");
        }
        if (!revision.finalized()) {
            return JobHandlingResult.failure("DOCUMENT_NOT_FINALIZED",
                    "document revision must be finalized before generating assets");
        }
        if (objectStorage.isEmpty()) {
            return JobHandlingResult.failure("ASSET_STORAGE_NOT_CONFIGURED",
                    "object storage is not configured");
        }

        List<AssetType> ordered = payload.assetTypes().stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
        List<String> failed = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        int total = ordered.size();
        for (int step = 0; step < total; step++) {
            AssetType type = ordered.get(step);
            if (context.cancellationRequested()) {
                return JobHandlingResult.failure("ASSET_CANCELLED", "asset generation was cancelled");
            }
            // Never regress below the progress the JobWorker already reported (>= 1).
            context.progress(Math.max(1, (step * 100) / total), "ASSET_" + type.name() + "_STARTED");
            try {
                generateOne(subSceneId, type, revision, context);
                context.progress(((step + 1) * 100) / total, "ASSET_" + type.name() + "_DONE");
            } catch (AssetBlockedException blockedException) {
                blocked.add(type.name());
                context.progress(((step + 1) * 100) / total, "ASSET_" + type.name() + "_BLOCKED");
            } catch (Exception exception) {
                failed.add(type.name());
                LOGGER.error("Asset generation failed for job {} type {}: {}", jobId, type,
                        exception.getClass().getSimpleName());
                context.progress(((step + 1) * 100) / total, "ASSET_" + type.name() + "_FAILED");
            }
        }
        if (!blocked.isEmpty()) {
            LOGGER.warn("Asset generation job {}: BLOCKED (no prerequisite) types: {}", jobId, blocked);
        }
        if (failed.isEmpty()) {
            return JobHandlingResult.success(subSceneId.toString());
        }
        return JobHandlingResult.failure("ASSET_GENERATION_PARTIAL",
                "failed types: " + String.join(",", failed));
    }

    private void generateOne(UUID subSceneId, AssetType type, DocumentRevision revision, WorkerJobContext context)
            throws Exception {
        Asset asset = assetService.beginGeneration(subSceneId, type, revision.id());
        List<MaterialSelection> holdout = List.of();
        if (type == AssetType.EVALUATION_SET) {
            holdout = assetService.holdoutSelection(subSceneId);
            if (holdout.isEmpty()) {
                String reason = "no READY LABELED_HOLDOUT binding for the latest round";
                assetService.markBlocked(asset.id(), reason);
                throw new AssetBlockedException(reason);
            }
        }
        try {
            byte[] bundle;
            if (type == AssetType.EVALUATION_SET) {
                // Only safe identity + holdout metadata reaches the renderer; no document content.
                bundle = contentFactory.buildEvaluationSet(subSceneId, asset.version(),
                        revision.revision(), revision.contentHash(), holdout);
            } else {
                bundle = contentFactory.build(subSceneId, type, asset.version(), revision, holdout);
            }
            String checksum = sha256(bundle);
            String objectKey = "assets/" + subSceneId + "/" + type.name().toLowerCase()
                    + "/v" + asset.version() + "-" + revision.contentHash().substring(0, 12) + "/bundle.zip";
            ObjectStoragePort port = objectStorage.orElseThrow();
            port.put(ObjectStoragePort.StorageZone.ASSETS, objectKey, bundle, "application/zip");
            assetService.markReady(asset.id(), objectKey, checksum);
        } catch (Exception exception) {
            try {
                assetService.markFailed(asset.id(), safeMessage(exception));
            } catch (RuntimeException ignored) {
                // The failure is reported by the job result; the DB state is best-effort.
            }
            throw exception;
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    public record Payload(Set<AssetType> assetTypes, UUID documentRevisionId) {
    }

    /** Internal marker: the asset was intentionally BLOCKED, not a generation failure. */
    private static final class AssetBlockedException extends RuntimeException {
        AssetBlockedException(String message) {
            super(message);
        }
    }
}
