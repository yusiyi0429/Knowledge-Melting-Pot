package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {
    Optional<DocumentRevision> findLatest(UUID documentId);

    Optional<DocumentRevision> findRevision(UUID revisionId);

    List<DocumentRevision> findRevisions(UUID documentId);

    DocumentRevision saveNextRevision(
            UUID documentId,
            UUID subSceneId,
            Long expectedRevision,
            UUID revisionId,
            String content,
            String contentHash,
            String revisionNote,
            boolean finalized,
            UUID actorId,
            Instant now);
}
