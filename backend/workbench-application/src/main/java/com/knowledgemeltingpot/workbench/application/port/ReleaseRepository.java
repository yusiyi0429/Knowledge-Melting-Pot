package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Release;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseRepository {
    void lockScene(UUID sceneId);

    boolean isFinalizedDocumentRevision(UUID revisionId, UUID subSceneId);

    Release savePublished(Release release, List<ReleaseItemSnapshot> items);

    Optional<Release> find(UUID releaseId);

    Optional<Release> findLatestPublished(UUID sceneId);

    List<ReleaseItemSnapshot> findItems(UUID releaseId);
}
