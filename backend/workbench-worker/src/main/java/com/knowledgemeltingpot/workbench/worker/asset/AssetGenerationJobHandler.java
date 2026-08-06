package com.knowledgemeltingpot.workbench.worker.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.LeasedJob;
import com.knowledgemeltingpot.workbench.application.port.AgentExecutionAttemptRepository;
import com.knowledgemeltingpot.workbench.application.port.AssetGenerationWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.service.AssetService;
import com.knowledgemeltingpot.workbench.application.service.AssetService.FrozenAgentConfiguration;
import com.knowledgemeltingpot.workbench.application.service.DocumentService;
import com.knowledgemeltingpot.workbench.domain.Asset;
import com.knowledgemeltingpot.workbench.domain.AgentExecutionAttempt;
import com.knowledgemeltingpot.workbench.domain.AgentExecutionAttemptStatus;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.worker.JobHandler;
import com.knowledgemeltingpot.workbench.worker.JobHandlingResult;
import com.knowledgemeltingpot.workbench.worker.WorkerJobContext;
import com.knowledgemeltingpot.workbench.worker.agent.AgentAssetGenerationWorkflowAdapter;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
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
    private final Optional<AssetGenerationWorkflowPort> workflow;
    private final AgentExecutionAttemptRepository attempts;
    private final Clock clock;

    public AssetGenerationJobHandler(AssetService assetService, DocumentService documentService,
            Optional<ObjectStoragePort> objectStorage, AssetContentFactory contentFactory,
            ObjectMapper objectMapper, Optional<AssetGenerationWorkflowPort> workflow,
            AgentExecutionAttemptRepository attempts, Clock clock) {
        this.assetService = assetService;
        this.documentService = documentService;
        this.objectStorage = objectStorage;
        this.contentFactory = contentFactory;
        this.objectMapper = objectMapper;
        this.workflow = workflow;
        this.attempts = attempts;
        this.clock = clock;
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
        if (payload.agentConfigurations() == null
                || !payload.agentConfigurations().keySet().containsAll(payload.assetTypes())) {
            return JobHandlingResult.failure("ASSET_AGENT_CONFIGURATION_MISSING",
                    "asset generation payload does not contain all frozen Agent configurations");
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
        List<String> sourceRefCodes = payload.assetTypes().stream().allMatch(type -> type == AssetType.EVALUATION_SET)
                ? List.of()
                : sourceRefCodes(revision.content());
        int total = ordered.size();
        for (int step = 0; step < total; step++) {
            AssetType type = ordered.get(step);
            if (context.cancellationRequested()) {
                return JobHandlingResult.failure("ASSET_CANCELLED", "asset generation was cancelled");
            }
            // Never regress below the progress the JobWorker already reported (>= 1).
            context.progress(Math.max(1, (step * 100) / total), "ASSET_" + type.name() + "_STARTED");
            try {
                generateOne(leasedJob, subSceneId, type, revision, sourceRefCodes,
                        payload.agentConfigurations().get(type), context);
                context.progress(runningProgress(step + 1, total), "ASSET_" + type.name() + "_DONE");
            } catch (AssetBlockedException blockedException) {
                blocked.add(type.name());
                context.progress(runningProgress(step + 1, total), "ASSET_" + type.name() + "_BLOCKED");
            } catch (Exception exception) {
                failed.add(type.name());
                LOGGER.error("Asset generation failed for job {} type {}: {}", jobId, type,
                        exception.getClass().getSimpleName());
                context.progress(runningProgress(step + 1, total), "ASSET_" + type.name() + "_FAILED");
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

    private void generateOne(LeasedJob leasedJob, UUID subSceneId, AssetType type, DocumentRevision revision,
            List<String> sourceRefCodes, FrozenAgentConfiguration configuration, WorkerJobContext context)
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
            AssetGenerationWorkflowPort port = workflow.orElseThrow(() ->
                    new AgentAssetGenerationWorkflowAdapter.WorkflowException("AGENT_RUNTIME_DISABLED"));
            List<AssetGenerationWorkflowPort.HoldoutSource> holdoutSources = holdout.stream()
                    .map(selection -> new AssetGenerationWorkflowPort.HoldoutSource(selection.material().id(),
                            selection.material().sha256(), selection.material().format().name(),
                            selection.material().sizeBytes()))
                    .toList();
            String inputHash = sha256((type + "|" + revision.contentHash() + "|"
                    + configuration.effectiveConfigHash() + "|" + holdoutSources)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            AgentExecutionAttempt attempt = attempts.insert(new AgentExecutionAttempt(UUID.randomUUID(),
                    leasedJob.job().id(), leasedJob.attempt(), configuration.role(), type, asset.id(),
                    configuration.modelConfigVersionId(), configuration.skillVersionId(),
                    configuration.roleConfigVersionId(), configuration.effectiveConfigHash(), inputHash, "",
                    AgentExecutionAttemptStatus.RUNNING, "", Instant.now(clock), null));
            AssetGenerationWorkflowPort.AssetDraft draft;
            try {
                draft = port.generate(new AssetGenerationWorkflowPort.AssetRequest(leasedJob.job().id(), type,
                        configuration.modelConfigVersionId(), configuration.skillVersionId(),
                        type == AssetType.EVALUATION_SET ? "" : revision.content(),
                        type == AssetType.EVALUATION_SET ? List.of() : sourceRefCodes, holdoutSources));
                String outputHash = sha256(objectMapper.writeValueAsBytes(draft));
                if (!attempts.markSucceeded(attempt.id(), outputHash, Instant.now(clock))) {
                    throw new IllegalStateException("Agent execution attempt could not be completed");
                }
            } catch (Exception exception) {
                attempts.markFailed(attempt.id(), failureCode(exception), Instant.now(clock));
                throw exception;
            }
            byte[] bundle;
            if (type == AssetType.EVALUATION_SET) {
                // Only safe identity + holdout metadata reaches the renderer; no document content.
                bundle = contentFactory.buildEvaluationSet(subSceneId, asset.version(),
                        revision.revision(), revision.contentHash(), holdout, draft);
            } else {
                bundle = contentFactory.build(subSceneId, type, asset.version(), revision, draft);
            }
            String checksum = sha256(bundle);
            String objectKey = "assets/" + subSceneId + "/" + type.name().toLowerCase()
                    + "/v" + asset.version() + "-" + revision.contentHash().substring(0, 12) + "/bundle.zip";
            ObjectStoragePort storagePort = objectStorage.orElseThrow();
            storagePort.put(ObjectStoragePort.StorageZone.ASSETS, objectKey, bundle, "application/zip");
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

    private static String failureCode(Exception exception) {
        if (exception instanceof AgentAssetGenerationWorkflowAdapter.WorkflowException workflowException) {
            return workflowException.code();
        }
        String value = exception.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
        return value.isBlank() ? "ASSET_AGENT_EXECUTION_FAILED"
                : value.substring(0, Math.min(100, value.length()));
    }

    private static int runningProgress(int completed, int total) {
        // JobLeaseRepository reserves 100 for the atomic RUNNING -> SUCCEEDED transition.
        return Math.min(99, (completed * 100) / total);
    }

    private static List<String> sourceRefCodes(String markdown) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[(SRC-[A-Za-z0-9_-]{1,100})]").matcher(markdown);
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
        while (matcher.find()) codes.add(matcher.group(1));
        return List.copyOf(codes);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    public record Payload(Set<AssetType> assetTypes, UUID documentRevisionId,
            java.util.Map<AssetType, FrozenAgentConfiguration> agentConfigurations) {
    }

    /** Internal marker: the asset was intentionally BLOCKED, not a generation failure. */
    private static final class AssetBlockedException extends RuntimeException {
        AssetBlockedException(String message) {
            super(message);
        }
    }
}
