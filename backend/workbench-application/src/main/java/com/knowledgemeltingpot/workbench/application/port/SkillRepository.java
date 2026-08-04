package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SkillRepository {
    List<Skill> findSkills(SkillKind kind, UUID sceneId);

    Optional<Skill> findById(UUID skillId);

    Skill insert(Skill skill);

    Optional<SkillVersion> findLatestVersion(UUID skillId);

    List<SkillVersion> findVersions(UUID skillId);

    Optional<SkillVersion> findVersion(UUID versionId);

    /** Appends the next immutable version (MAX(version) + 1) for the skill, using the caller-provided id. */
    SkillVersion insertVersion(Skill skill, UUID versionId, String manifestJson, String packageHash,
            UUID createdBy, Instant createdAt);
}
