package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRecord;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import com.knowledgemeltingpot.workbench.application.port.MaterialRepository;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialService {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final MaterialRepository materialRepository;
    private final SceneRepository sceneRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final JobService jobService;
    private final AuditService auditService;
    private final Clock clock;

    public MaterialService(MaterialRepository materialRepository, SceneRepository sceneRepository,
            IdempotencyRepository idempotencyRepository, JobService jobService, AuditService auditService,
            Clock clock) {
        this.materialRepository = materialRepository;
        this.sceneRepository = sceneRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.jobService = jobService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public MaterialUploadIntentResult createUploadIntent(MaterialUploadCommand command, UUID actorId,
            String idempotencyKey, String traceId) {
        if (command == null || command.roundId() == null || command.partition() == null
                || command.shareScope() == null || actorId == null) {
            throw new IllegalArgumentException("roundId, partition, and shareScope are required");
        }
        if (command.regulatorySource() && command.partition() == MaterialPartition.LABELED_HOLDOUT) {
            throw new IllegalArgumentException("holdout material cannot be a regulatory alignment source");
        }
        MaterialUploadPolicy.ValidatedUpload upload = MaterialUploadPolicy.validate(command.fileName(),
                command.sizeBytes(), command.mediaType(), command.sha256());
        ExtractionRound round = sceneRepository.findRound(command.roundId())
                .orElseThrow(() -> new NotFoundException("extraction round does not exist"));
        List<SubScene> targetSubScenes = resolveTargets(round, command.subSceneIds(), command.shareScope());
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

        Material material = materialRepository.insert(new Material(materialId, upload.fileName(), upload.format(),
                upload.mediaType(), "quarantine/" + materialId, upload.sha256(), upload.sizeBytes(),
                MaterialStatus.PENDING_UPLOAD, now, now));
        List<RoundMaterial> bindings = targetSubScenes.stream()
                .sorted(Comparator.comparing(SubScene::id))
                .map(subScene -> new RoundMaterial(UUID.randomUUID(), material.id(), round.id(), subScene.id(),
                        command.partition(), command.shareScope(), command.regulatorySource(), true, now))
                .toList();
        materialRepository.insertBindings(bindings);
        MaterialUploadIntent intent = materialRepository.insertIntent(new MaterialUploadIntent(intentId,
                material.id(), actorId, null, "", now, null));
        auditService.record(actorId, "MATERIAL_UPLOAD_INTENT_CREATED", "MATERIAL", material.id(), Map.of(
                "roundId", round.id(),
                "format", material.format(),
                "sizeBytes", material.sizeBytes(),
                "partition", command.partition(),
                "shareScope", command.shareScope()), traceId);
        return new MaterialUploadIntentResult(intent, material, bindings, false);
    }

    @Transactional
    public JobSubmission completeUpload(UUID intentId, String clientEtag, UUID actorId, String traceId) {
        String normalizedEtag = validateEtag(clientEtag);
        MaterialUploadIntent intent = materialRepository.lockIntent(intentId)
                .orElseThrow(() -> new NotFoundException("upload intent does not exist"));
        if (!intent.createdBy().equals(actorId)) {
            throw new NotFoundException("upload intent does not exist");
        }
        if (intent.validationJobId() != null) {
            return new JobSubmission(jobService.get(intent.validationJobId()), true);
        }
        Material material = materialRepository.findById(intent.materialId())
                .orElseThrow(() -> new ConflictException("upload intent material is unavailable"));
        Material uploaded = material.transitionTo(MaterialStatus.UPLOADED, Instant.now(clock));
        if (!materialRepository.transitionStatus(material.id(), material.status(), uploaded.status(),
                uploaded.updatedAt())) {
            throw new ConflictException("material is not awaiting upload completion");
        }

        JobSubmission submission = jobService.submit(JobType.INGEST, "MATERIAL", material.id(), Map.of(
                "intentId", intent.id(),
                "objectKey", material.objectKey(),
                "declaredEtag", normalizedEtag,
                "expectedSha256", material.sha256(),
                "expectedSizeBytes", material.sizeBytes(),
                "format", material.format(),
                "validationOnly", true), actorId, null, traceId);
        Instant completedAt = Instant.now(clock);
        if (!materialRepository.completeIntent(intent.id(), submission.job().id(), normalizedEtag, completedAt)) {
            throw new ConflictException("upload intent completion raced with another request");
        }
        auditService.record(actorId, "MATERIAL_UPLOAD_CLAIMED", "MATERIAL", material.id(),
                Map.of("validationJobId", submission.job().id()), traceId);
        return submission;
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
            return new MaterialUploadIntentResult(intent, material,
                    materialRepository.findBindings(material.id()), true);
        }).orElse(null);
    }

    private String requestHash(MaterialUploadCommand command, MaterialUploadPolicy.ValidatedUpload upload,
            List<SubScene> targets) {
        String targetIds = targets.stream().map(SubScene::id).sorted().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        return Hashes.sha256(String.join("\n",
                upload.fileName(), Long.toString(upload.sizeBytes()), upload.mediaType(), upload.sha256(),
                command.roundId().toString(), targetIds, command.partition().name(), command.shareScope().name(),
                Boolean.toString(command.regulatorySource())));
    }

    private String validateEtag(String clientEtag) {
        if (clientEtag == null || clientEtag.isBlank() || clientEtag.length() > 200
                || clientEtag.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("etag must be 1 to 200 printable characters");
        }
        return clientEtag.trim();
    }
}
