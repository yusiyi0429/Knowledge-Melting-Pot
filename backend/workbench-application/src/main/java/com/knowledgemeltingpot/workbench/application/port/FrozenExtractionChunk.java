package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import com.knowledgemeltingpot.workbench.domain.MaterialChunk;
import com.knowledgemeltingpot.workbench.domain.MaterialPartition;
import java.util.UUID;

public record FrozenExtractionChunk(
        UUID materialId,
        String materialSha256,
        MaterialPartition partition,
        MaterialChunk chunk,
        KnowledgeIr.SourceRef sourceRef) {

    public FrozenExtractionChunk {
        if (materialId == null || materialSha256 == null || partition == null || chunk == null || sourceRef == null) {
            throw new IllegalArgumentException("frozen extraction chunk fields are required");
        }
        if (partition == MaterialPartition.LABELED_HOLDOUT) {
            throw new IllegalArgumentException("HOLDOUT chunks cannot enter an extraction run");
        }
        if (!materialId.equals(sourceRef.materialId()) || !chunk.id().equals(sourceRef.chunkId())) {
            throw new IllegalArgumentException("source reference does not match the frozen chunk");
        }
    }
}
