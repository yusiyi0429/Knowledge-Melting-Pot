package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ExtractionRun;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExtractionRunRepository {
    void insert(ExtractionRun run, List<FrozenExtractionChunk> chunks);

    Optional<ExtractionRun> findByJobId(UUID jobId);

    List<FrozenExtractionChunk> findChunks(UUID runId);

    Optional<String> findMapResult(UUID runId, UUID chunkId);

    void insertMapResult(UUID runId, UUID chunkId, String resultJson, String resultHash, Instant createdAt);

    void insertReduceResult(UUID runId, KnowledgeIr ir, String irHash, Instant createdAt);

    void updateStage(UUID runId, ExtractionRun.Stage stage, Instant updatedAt);
}
