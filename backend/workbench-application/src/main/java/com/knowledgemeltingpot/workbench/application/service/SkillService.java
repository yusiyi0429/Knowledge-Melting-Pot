package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRecord;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillService {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final SkillRepository skillRepository;
    private final SceneRepository sceneRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final SkillManifestValidator manifestValidator;
    private final AuditService auditService;
    private final Clock clock;

    public SkillService(SkillRepository skillRepository, SceneRepository sceneRepository,
            IdempotencyRepository idempotencyRepository, SkillManifestValidator manifestValidator,
            AuditService auditService, Clock clock) {
        this.skillRepository = skillRepository;
        this.sceneRepository = sceneRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.manifestValidator = manifestValidator;
        this.auditService = auditService;
        this.clock = clock;
    }

    public record SkillWithVersion(Skill skill, SkillVersion latest) {
    }

    public record SkillDetail(Skill skill, List<SkillVersion> versions) {
    }

    public record SkillCreation(Skill skill, SkillVersion version, boolean replayed) {
    }

    public record SkillVersionCreation(SkillVersion version, boolean replayed) {
    }

    @Transactional(readOnly = true)
    public List<SkillWithVersion> list(SkillKind kind, UUID sceneId) {
        return skillRepository.findSkills(kind, sceneId).stream()
                .map(skill -> new SkillWithVersion(skill, skillRepository.findLatestVersion(skill.id()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SkillDetail detail(UUID skillId) {
        Skill skill = requireSkill(skillId);
        return new SkillDetail(skill, skillRepository.findVersions(skillId));
    }

    @Transactional
    public SkillCreation createTemplate(String name, String description, String manifestJson, String packageHash,
            UUID actorId, String idempotencyKey, String traceId) {
        String normalizedName = requireName(name);
        String normalizedDescription = description == null ? "" : description.trim();
        String normalizedManifest = manifestValidator.validate(manifestJson);
        validatePackageHash(packageHash);
        String hash = requestHash(normalizedName, normalizedDescription, normalizedManifest, packageHash, "");
        SkillCreation replay = findSkillReplay(actorId, idempotencyKey, hash);
        if (replay != null) {
            return replay;
        }
        Instant now = Instant.now(clock);
        UUID skillId = UUID.randomUUID();
        reserve(actorId, idempotencyKey, hash, "SKILL", skillId, now);
        Skill skill = new Skill(skillId, normalizedName, SkillKind.TEMPLATE, normalizedDescription,
                null, null, null, actorId, now);
        skillRepository.insert(skill);
        SkillVersion version = skillRepository.insertVersion(skill, UUID.randomUUID(), normalizedManifest,
                packageHash, actorId, now);
        auditService.record(actorId, "SKILL_CREATED", "SKILL", skill.id(), Map.of(
                "kind", skill.kind().name(), "version", version.version(), "packageHash", packageHash), traceId);
        return new SkillCreation(skill, version, false);
    }

    @Transactional
    public SkillCreation forkInstance(UUID templateSkillId, UUID sceneId, UUID actorId, String idempotencyKey,
            String traceId) {
        if (sceneId == null) {
            throw new IllegalArgumentException("sceneId is required to fork a scene instance");
        }
        sceneRepository.findScene(sceneId)
                .orElseThrow(() -> new NotFoundException("scene not found: " + sceneId));
        Skill template = requireSkill(templateSkillId);
        if (template.kind() != SkillKind.TEMPLATE) {
            throw new IllegalArgumentException("only a TEMPLATE skill can be forked into an instance");
        }
        SkillVersion source = skillRepository.findLatestVersion(template.id())
                .orElseThrow(() -> new NotFoundException("source template has no version"));
        if (!source.skillId().equals(template.id())) {
            throw new IllegalStateException("source version does not belong to the source skill");
        }
        String hash = requestHash(template.id().toString(), sceneId.toString(), source.id().toString());
        SkillCreation replay = findSkillReplay(actorId, idempotencyKey, hash);
        if (replay != null) {
            return replay;
        }
        Instant now = Instant.now(clock);
        UUID skillId = UUID.randomUUID();
        reserve(actorId, idempotencyKey, hash, "SKILL", skillId, now);
        Skill instance = template.fork(skillId, sceneId, source.id(), actorId, now);
        skillRepository.insert(instance);
        SkillVersion version = skillRepository.insertVersion(instance, UUID.randomUUID(), source.manifestJson(),
                source.packageHash(), actorId, now);
        auditService.record(actorId, "SKILL_INSTANCE_CREATED", "SKILL", instance.id(), Map.of(
                "sourceSkillId", template.id(), "sourceVersion", source.version(), "sceneId", sceneId,
                "version", version.version()), traceId);
        return new SkillCreation(instance, version, false);
    }

    @Transactional
    public SkillVersionCreation createVersion(UUID skillId, String manifestJson, String packageHash, UUID actorId,
            String idempotencyKey, String traceId) {
        Skill skill = requireSkill(skillId);
        if (skill.kind() != SkillKind.INSTANCE) {
            throw new IllegalArgumentException("only an INSTANCE skill can receive new versions");
        }
        String normalizedManifest = manifestValidator.validate(manifestJson);
        validatePackageHash(packageHash);
        String hash = requestHash(skillId.toString(), normalizedManifest, packageHash);
        SkillVersionCreation replay = idempotencyRepository.find(scope(actorId), key(idempotencyKey))
                .filter(record -> record.requestHash().equals(hash) && record.resourceType().equals("SKILL_VERSION"))
                .flatMap(record -> skillRepository.findVersion(record.resourceId())
                        .map(version -> new SkillVersionCreation(version, true)))
                .orElse(null);
        if (replay != null) {
            return replay;
        }
        Instant now = Instant.now(clock);
        UUID versionId = UUID.randomUUID();
        reserve(actorId, idempotencyKey, hash, "SKILL_VERSION", versionId, now);
        // The reserved id and the persisted id are the same, so a replay finds the real version.
        SkillVersion version = skillRepository.insertVersion(skill, versionId, normalizedManifest, packageHash,
                actorId, now);
        auditService.record(actorId, "SKILL_VERSION_CREATED", "SKILL", skill.id(), Map.of(
                "version", version.version(), "packageHash", packageHash), traceId);
        return new SkillVersionCreation(version, false);
    }

    private SkillCreation findSkillReplay(UUID actorId, String idempotencyKey, String hash) {
        return idempotencyRepository.find(scope(actorId), key(idempotencyKey))
                .filter(record -> record.requestHash().equals(hash) && record.resourceType().equals("SKILL"))
                .flatMap(record -> skillRepository.findById(record.resourceId())
                        .map(skill -> new SkillCreation(skill, skillRepository.findLatestVersion(skill.id())
                                .orElse(null), true)))
                .orElse(null);
    }

    private void reserve(UUID actorId, String idempotencyKey, String hash, String resourceType, UUID resourceId,
            Instant now) {
        if (key(idempotencyKey).isBlank()) {
            return;
        }
        if (!idempotencyRepository.tryReserve(new IdempotencyRecord(scope(actorId), key(idempotencyKey), hash,
                resourceType, resourceId, now, now.plus(IDEMPOTENCY_TTL)))) {
            throw new com.knowledgemeltingpot.workbench.application.error.ConflictException(
                    "idempotency key is already being processed");
        }
    }

    private Skill requireSkill(UUID skillId) {
        return skillRepository.findById(skillId)
                .orElseThrow(() -> new NotFoundException("skill not found: " + skillId));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("name must not exceed 200 characters");
        }
        return trimmed;
    }

    private static void validatePackageHash(String packageHash) {
        if (packageHash == null || !packageHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "packageHash must be a lowercase 64-character hexadecimal digest");
        }
    }

    private static String scope(UUID actorId) {
        return "skill-write:" + actorId;
    }

    private static String key(String idempotencyKey) {
        return idempotencyKey == null ? "" : idempotencyKey.trim();
    }

    private static String requestHash(String... parts) {
        return Hashes.sha256(String.join("\n", parts));
    }
}
