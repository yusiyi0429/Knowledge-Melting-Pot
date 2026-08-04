package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.AgentMountScope;
import com.knowledgemeltingpot.workbench.domain.AgentMountVersion;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.AgentRoleTemplateVersion;
import com.knowledgemeltingpot.workbench.domain.ConfigurationImportPreview;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentMountRepository {
    void lockScope(AgentMountScope scope, UUID scopeId);

    List<AgentMountVersion> findLatest(AgentMountScope scope, UUID scopeId);

    Optional<AgentMountVersion> findLatest(AgentMountScope scope, UUID scopeId, AgentRole role);

    Optional<AgentMountVersion> findVersion(UUID versionId);

    AgentMountVersion insert(AgentMountVersion version, UUID sceneId);

    List<AgentRoleTemplateVersion> findLatestTemplates();

    ConfigurationImportPreview insertImport(ConfigurationImportPreview preview);

    Optional<ConfigurationImportPreview> findImport(UUID importId);

    boolean markImportApplied(UUID importId, UUID actorId, Instant appliedAt);
}
