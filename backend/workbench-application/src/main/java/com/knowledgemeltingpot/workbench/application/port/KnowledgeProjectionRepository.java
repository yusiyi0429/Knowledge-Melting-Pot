package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeProjectionRepository {
    void insert(UUID revisionId, KnowledgeIr ir, String irHash, Instant createdAt);

    Optional<KnowledgeIr> find(UUID revisionId);
}
