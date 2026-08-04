package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the active embedding profile version. An empty result
 * means no real embedding provider is configured: the worker must surface that
 * as a diagnosable unconfigured state instead of fabricating vectors.
 */
public interface EmbeddingProfileVersionRepository {

    EmbeddingProfileVersion insertAndActivate(EmbeddingProfileVersion profile, UUID activatedBy,
            Instant activatedAt);

    List<EmbeddingProfileVersion> findAll();

    Optional<EmbeddingProfileVersion> findById(UUID id);

    Optional<EmbeddingProfileVersion> findActive();
}
