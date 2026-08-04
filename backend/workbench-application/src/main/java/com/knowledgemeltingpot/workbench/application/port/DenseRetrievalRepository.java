package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.DenseRetrievalResult;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.util.List;
import java.util.UUID;

public interface DenseRetrievalRepository {
    List<DenseRetrievalResult> searchKnowledge(UUID roundId, UUID subSceneId,
            EmbeddingProfileVersion profile, List<Float> queryVector, int limit);
}
