package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.util.Optional;

/**
 * Persistence port for the active embedding profile version. An empty result
 * means no real embedding provider is configured: the worker must surface that
 * as a diagnosable unconfigured state instead of fabricating vectors.
 */
public interface EmbeddingProfileVersionRepository {

    EmbeddingProfileVersion insert(EmbeddingProfileVersion profile);

    Optional<EmbeddingProfileVersion> findActive();
}
