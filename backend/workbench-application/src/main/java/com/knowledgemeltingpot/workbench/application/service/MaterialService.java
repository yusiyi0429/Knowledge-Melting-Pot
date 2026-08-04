package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRecord;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import com.knowledgemeltingpot.workbench.application.port.ExplorationRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.ObjectStoragePort;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import com.knowledgemeltingpot.workbench.domain.JobType;
import com.knowledgemeltingpot.workbench.domain.Material;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import com.knowledgemeltingpot.workbench.domain.MaterialShareScope;
import com.knowledgemeltingpot.workbench.domain.MaterialStatus;
import com.knowledgemeltingpot.workbench.domain.MaterialUploadIntent;
import com.knowledgemeltingpot.workbench.domain.RoundMaterial;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import com.knowledgemeltingpot.workbench.domain.UploadState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialService {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration PRESIGN_UPLOAD_TIMEOUT = Duration.ofMinutes(15);

    private final MaterialRepository materialRepository;
    private final ExplorationRepository explorationRepository;
    private final SceneRepository sceneRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final JobService jobService;
    private final AuditService auditService;
    private final Optional<ObjectStoragePort> objectStorage;
    private final Clock clock;

    public MaterialService(MaterialRepository materialRepository, ExplorationRepository explorationRepository,
            SceneRepository sceneRepository,
            IdempotencyRepository idempotencyRepository, JobService jobService, AuditService auditService,
            Optional<ObjectStoragePort> objectStorage, Clock clock) {
        this.materialRepository = materialRepository;
        this.explorationRepository = explorationRepository;
        this.sceneRepository = sceneRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.jobService = jobService;
        this.auditService = auditService;
        this.objectStorage = objectStorage;
        this.clock = clock;
    }

    @Transactional
    public MaterialUploadIntentResult createUploadIntent(MaterialUploadCommand command, UUID actorId,
            String idempotencyKey, String traceId) {
        if (command == null || command.partition() == null
                || command.shareScope() == null || actorId == null) {
            throw new IllegalArgumentException("partition and shareScope are required");
        }
        boolean stagedExploration = command.explorationSessionId() != null;
        if (stagedExploration == (command.roundId() != null)) {
            throw new IllegalArgumentException("exactly one of roundId or explorationSessionId is required");
        }
        if (stagedExploration && (command.partition() != MaterialPartition.SOURCE
                || command.shareScope() != MaterialShareScope.ROUND || command.regulatorySource()
                || !command.subSceneIds().isEmpty())) {
            throw new IllegalArgumentException("exploration staging only accepts private SOURCE materials");
        }
        if (command.regulatorySource() && command.partition() == MaterialPartition.LABELED_HOLDOUT) {
            throw new IllegalArgumentException("holdout material cannot be a regulatory alignment source");
        }
        MaterialUploadPolicy.ValidatedUpload upload = MaterialUploadPolicy.validate(command.fileName(),
                command.sizeBytes(), command.mediaType(), command.sha256());
        ExtractionRound round = stagedExploration ? null : sceneRepository.findRound(command.roundId())
                .orElseThrow(() -> new NotFoundException("extraction round does not exist"));
        if (stagedExploration && explorationRepository.find(command.explorationSessionId()).isEmpty()) {
            throw new NotFoundException("exploration session does not exist");
        }
        List<SubScene> targetSubScenes = stagedExploration ? List.of()
                : resolveTargets(round, command.subSceneIds(), command.shareScope());
        String requestHash = requestHash(command, upload, targetSubScenes);
        String normalizedKey = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!normalizedKey.isBlank() && (normalizedKey.length() < 8 || normalizedKey.length() > 128)) {
            throw new IllegalArgumentException("Idempotency-Key must be between 8 and 128 characters");
        }
        String scope = "material-upload:" + actorId;
        if (!normalizedKey.isBlank()) {
            MaterialUploadIntentResult replay = replay(scope, normalizedKey, requestHash);
            if (replay != null) {
                return replay;
            }
        }

        Instant now = Instant.now(clock);
        UUID intentId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        if (!normalizedKey.isBlank()) {
            IdempotencyRecord reservation = new IdempotencyRecord(scope, normalizedKey, requestHash,
                    "MATERIAL_UPLOAD_INTENT", intentId, now, now.plus(IDEMPOTENCY_TTL));
            if (!idempotencyRepository.tryReserve(reservation)) {
                MaterialUploadIntentResult replay = replay(scope, normalizedKey, requestHash);
                if (replay != null) {
                    return replay;
                }
                throw new ConflictException("idempotency key is already being processed");
            }
        }

        String quarantineKey = "quarantine/" + materialId;
        Material material = materialRepository.insert(new Material(materialId, upload.fileName(), upload.format(),
                upload.mediaType(), quarantineKey, upload.sha256(), upload.sizeBytes(),
                MaterialStatus.PENDING_UPLOAD, now, now));
        List<RoundMaterial> bindings = targetSubScenes.stream()
                .sorted(Comparator.comparing(SubScene::id))
                .map(subScene -> new RoundMaterial(UUID.randomUUID(), material.id(), round.id(), subScene.id(),
                        command.partition(), command.shareScope(), command.regulatorySource(), true, now))
                .toList();
        materialRepository.insertBindings(bindings);
        if (stagedExploration && !explorationRepository.linkMaterial(command.explorationSessionId(), material.id(), now)) {
            throw new ConflictException("exploration session no longer accepts staged materials");
        }

        MaterialUploadIntent intent;
        List<ObjectStoragePort.PresignedPart> presignedParts;
        boolean configured;
        String uploadMode;
        String capabilityStatus;
        String messageCode;
        if (objectStorage.isPresent()) {
            ObjectStoragePort port = objectStorage.get();
            ObjectStoragePort.MultipartUpload multipart = port.initiateMultipart(ObjectStoragePort.StorageZone.QUARANTINE,
                    quarantineKey, material.mediaType(), material.sizeBytes(), PRESIGN_UPLOAD_TIMEOUT);
            intent = materialRepository.insertIntent(MaterialUploadIntent.multipart(intentId, materialId, actorId, now,
                    multipart.uploadId(), multipart.objectKey(), multipart.partSize(), multipart.partCount(),
                    multipart.expiresAt()));
            presignedParts = port.presignParts(ObjectStoragePort.StorageZone.QUARANTINE, multipart.uploadId(),
                    multipart.objectKey(), 1, multipart.partCount(), PRESIGN_UPLOAD_TIMEOUT);
            configured = true;
            uploadMode = "MULTIPART_PRESIGNED";
            capabilityStatus = "MULTIPART_PRESIGNED";
            messageCode = "material.upload.multipart-presigned";
        } else {
            intent = materialRepository.insertIntent(MaterialUploadIntent.declarationOnly(intentId, materialId, actorId, now));
            presignedParts = List.of();
            configured = false;
            uploadMode = "DECLARATION_ONLY";
            capabilityStatus = "OBJECT_STORAGE_NOT_CONFIGURED";
            messageCode = "material.upload.object-storage-not-configured";
        }
        Map<String, Object> auditDetails = new java.util.LinkedHashMap<>();
        if (round != null) auditDetails.put("roundId", round.id());
        if (stagedExploration) auditDetails.put("explorationSessionId", command.explorationSessionId());
        auditDetails.put("format", material.format());
        auditDetails.put("sizeBytes", material.sizeBytes());
        auditDetails.put("partition", command.partition());
        auditDetails.put("shareScope", command.shareScope());
        auditDetails.put("objectStorageConfigured", configured);
        auditService.record(actorId, "MATERIAL_UPLOAD_INTENT_CREATED", "MATERIAL", material.id(),
                auditDetails, traceId);
        return new MaterialUploadIntentResult(intent, material, bindings, false, configured, uploadMode,
                capabilityStatus, messageCode, presignedParts);
    }

    @Transactional
    public JobSubmission completeUpload(UUID intentId, List<UploadedPart> parts, UUID actorId, String traceId) {
        MaterialUploadIntent intent = materialRepository.lockIntent(intentId)
                .orElseThrow(() -> new NotFoundException("upload intent does not exist"));
        if (!intent.createdBy().equals(actorId)) {
            throw new NotFoundException("upload intent does not exist");
        }
        if (intent.validationJobId() != null) {
            return new JobSubmission(jobService.get(intent.validationJobId()), true);
        }
        if (intent.uploadState() == UploadState.ABORTED || intent.uploadState() == UploadState.EXPIRED) {
            throw new ConflictException("upload intent is no longer active");
        }
        Material material = materialRepository.findById(intent.materialId())
                .orElseThrow(() -> new ConflictException("upload intent material is unavailable"));

        if (objectStorage.isPresent() && intent.isMultipart()) {
            return completeMultipartUpload(intent, parts, material, actorId, traceId);
        }
        return completeDeclarationOnly(intent, material, actorId, traceId);
    }

    private JobSubmission completeMultipartUpload(MaterialUploadIntent intent, List<UploadedPart> parts,
            Material material, UUID actorId, String traceId) {
        ObjectStoragePort port = objectStorage.orElseThrow();
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("uploaded parts are required");
        }
        List<ObjectStoragePort.UploadedPart> uploadedParts = parts.stream()
                .map(part -> new ObjectStoragePort.UploadedPart(part.partNumber(), part.etag()))
                .sorted(Comparator.comparingInt(ObjectStoragePort.UploadedPart::partNumber))
                .toList();
        if (uploadedParts.size() != intent.partCount()) {
            throw new IllegalArgumentException("uploaded parts must match the declared part count");
        }
        for (int index = 0; index < uploadedParts.size(); index++) {
            if (uploadedParts.get(index).partNumber() != index + 1) {
                throw new IllegalArgumentException("uploaded part numbers must be contiguous from one");
            }
        }
        if (!materialRepository.incrementCompletionAttempt(intent.id())
                || !materialRepository.updateIntentState(intent.id(), UploadState.COMPLETING)) {
            throw new ConflictException("upload intent state changed during completion");
        }
        ObjectStoragePort.ObjectHead head = port.completeMultipart(ObjectStoragePort.StorageZone.QUARANTINE,
                intent.storageUploadId(), intent.quarantineObjectKey(), uploadedParts);
        if (head.sizeBytes() != material.sizeBytes()) {
            throw new ConflictException("uploaded size does not match declaration");
        }
        return finalizeCompletion(intent, material, head.etag(), actorId, traceId);
    }

    private JobSubmission completeDeclarationOnly(MaterialUploadIntent intent, Material material, UUID actorId,
            String traceId) {
        materialRepository.incrementCompletionAttempt(intent.id());
        return finalizeCompletion(intent, material, "", actorId, traceId);
    }

    private JobSubmission finalizeCompletion(MaterialUploadIntent intent, Material material, String etag,
            UUID actorId, String traceId) {
        Instant now = Instant.now(clock);
        Material uploaded = material.transitionTo(MaterialStatus.UPLOADED, now);
        if (!materialRepository.transitionStatus(material.id(), material.status(), uploaded.status(),
                uploaded.updatedAt())) {
            throw new ConflictException("material is not awaiting upload completion");
        }

        JobSubmission submission = jobService.submit(JobType.INGEST, "MATERIAL", material.id(), Map.of(
                "intentId", intent.id(),
                "objectKey", material.objectKey(),
                "clientEtag", etag,
                "expectedSha256", material.sha256(),
                "expectedSizeBytes", material.sizeBytes(),
                "format", material.format()), actorId, null, traceId);
        if (!materialRepository.completeIntent(intent.id(), submission.job().id(), etag, now)) {
            throw new ConflictException("upload intent completion raced with another request");
        }
        auditService.record(actorId, "MATERIAL_UPLOAD_COMPLETED", "MATERIAL", material.id(),
                Map.of("validationJobId", submission.job().id()), traceId);
        return submission;
    }

    @Transactional
    public void abortUpload(UUID intentId, UUID actorId, String traceId) {
        MaterialUploadIntent intent = materialRepository.lockIntent(intentId)
                .orElseThrow(() -> new NotFoundException("upload intent does not exist"));
        if (!intent.createdBy().equals(actorId)) {
            throw new NotFoundException("upload intent does not exist");
        }
        if (intent.uploadState() == UploadState.COMPLETED || intent.uploadState() == UploadState.ABORTED
                || intent.uploadState() == UploadState.EXPIRED) {
            throw new ConflictException("upload intent is already terminal");
        }
        Instant now = Instant.now(clock);
        if (objectStorage.isPresent() && intent.isMultipart()) {
            try {
                objectStorage.get().abortMultipart(ObjectStoragePort.StorageZone.QUARANTINE,
                        intent.storageUploadId(), intent.quarantineObjectKey());
            } catch (Exception exception) {
                // Best-effort abort; the DB state remains the source of truth.
            }
        }
        if (!materialRepository.abortIntent(intent.id(), now)) {
            throw new ConflictException("upload intent could not be aborted");
        }
        // An aborted intent must never leave its material permanently PENDING_UPLOAD.
        materialRepository.transitionStatus(intent.materialId(), MaterialStatus.PENDING_UPLOAD,
                MaterialStatus.INACTIVE, now);
        auditService.record(actorId, "MATERIAL_UPLOAD_ABORTED", "MATERIAL", intent.materialId(), Map.of(), traceId);
    }

    @Transactional(readOnly = true)
    public Material get(UUID materialId) {
        return materialRepository.findById(materialId)
                .orElseThrow(() -> new NotFoundException("material does not exist"));
    }

    @Transactional(readOnly = true)
    public List<RoundMaterial> bindings(UUID materialId) {
        get(materialId);
        return materialRepository.findBindings(materialId);
    }

    private List<SubScene> resolveTargets(ExtractionRound round, Set<UUID> requestedIds,
            MaterialShareScope shareScope) {
        SubScene primary = sceneRepository.findSubScene(round.subSceneId())
                .orElseThrow(() -> new NotFoundException("round sub-scene does not exist"));
        Set<UUID> targetIds = requestedIds == null || requestedIds.isEmpty()
                ? Set.of(primary.id())
                : new LinkedHashSet<>(requestedIds);
        if (!targetIds.contains(primary.id())) {
            throw new IllegalArgumentException("subSceneIds must include the round's sub-scene");
        }
        if (shareScope != MaterialShareScope.SCENE && targetIds.size() != 1) {
            throw new IllegalArgumentException("only SCENE sharing may target multiple sub-scenes");
        }
        return targetIds.stream().map(id -> sceneRepository.findSubScene(id)
                        .orElseThrow(() -> new NotFoundException("target sub-scene does not exist")))
                .peek(subScene -> {
                    if (!subScene.sceneId().equals(primary.sceneId())) {
                        throw new IllegalArgumentException("shared material targets must belong to one scene");
                    }
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaterialSelection> listWorkbenchMaterials(UUID roundId, UUID subSceneId) {
        if (roundId == null || subSceneId == null) {
            throw new IllegalArgumentException("roundId and subSceneId are required");
        }
        ExtractionRound round = sceneRepository.findRound(roundId)
                .orElseThrow(() -> new NotFoundException("extraction round does not exist"));
        sceneRepository.findSubScene(subSceneId)
                .orElseThrow(() -> new NotFoundException("sub-scene does not exist"));
        if (!round.subSceneId().equals(subSceneId)) {
            throw new IllegalArgumentException("round does not belong to the requested sub-scene");
        }
        return materialRepository.findWorkbenchMaterials(roundId, subSceneId);
    }

    private MaterialUploadIntentResult replay(String scope, String key, String requestHash) {
        return idempotencyRepository.find(scope, key).map(record -> {
            if (!record.requestHash().equals(requestHash)
                    || !record.resourceType().equals("MATERIAL_UPLOAD_INTENT")) {
                throw new ConflictException("idempotency key was used with a different request");
            }
            MaterialUploadIntent intent = materialRepository.findIntent(record.resourceId())
                    .orElseThrow(() -> new ConflictException("idempotent upload intent is unavailable"));
            Material material = materialRepository.findById(intent.materialId())
                    .orElseThrow(() -> new ConflictException("idempotent material is unavailable"));
            List<ObjectStoragePort.PresignedPart> parts = objectStorage.isPresent() && intent.isMultipart()
                    ? objectStorage.get().presignParts(ObjectStoragePort.StorageZone.QUARANTINE,
                            intent.storageUploadId(), intent.quarantineObjectKey(), 1, intent.partCount(),
                            PRESIGN_UPLOAD_TIMEOUT)
                    : List.of();
            return new MaterialUploadIntentResult(intent, material,
                    materialRepository.findBindings(material.id()), true, objectStorage.isPresent() && intent.isMultipart(),
                    intent.isMultipart() ? "MULTIPART_PRESIGNED" : "DECLARATION_ONLY",
                    objectStorage.isPresent() && intent.isMultipart() ? "MULTIPART_PRESIGNED"
                            : "OBJECT_STORAGE_NOT_CONFIGURED",
                    intent.isMultipart() ? "material.upload.multipart-presigned"
                            : "material.upload.object-storage-not-configured",
                    parts);
        }).orElse(null);
    }

    private String requestHash(MaterialUploadCommand command, MaterialUploadPolicy.ValidatedUpload upload,
            List<SubScene> targets) {
        String targetIds = targets.stream().map(SubScene::id).sorted().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        return Hashes.sha256(String.join("\n",
                upload.fileName(), Long.toString(upload.sizeBytes()), upload.mediaType(), upload.sha256(),
                command.roundId() == null ? "" : command.roundId().toString(),
                command.explorationSessionId() == null ? "" : command.explorationSessionId().toString(),
                targetIds, command.partition().name(), command.shareScope().name(),
                Boolean.toString(command.regulatorySource())));
    }

    public record UploadedPart(int partNumber, String etag) {
        public UploadedPart {
            if (partNumber < 1) {
                throw new IllegalArgumentException("partNumber must be positive");
            }
            if (etag == null || etag.isBlank()) {
                throw new IllegalArgumentException("etag is required");
            }
            if (etag.length() > 200) {
                throw new IllegalArgumentException("etag must not exceed 200 characters");
            }
        }
    }
}
