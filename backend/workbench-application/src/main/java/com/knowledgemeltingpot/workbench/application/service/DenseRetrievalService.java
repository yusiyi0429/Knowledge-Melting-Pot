package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.ConflictException;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.DenseRetrievalRepository;
import com.knowledgemeltingpot.workbench.application.port.EmbeddingPort;
import com.knowledgemeltingpot.workbench.application.port.EmbeddingProfileVersionRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.DenseRetrievalResult;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DenseRetrievalService {
    private final EmbeddingProfileVersionRepository profiles;
    private final List<EmbeddingPort> providers;
    private final DenseRetrievalRepository retrieval;
    private final SceneRepository scenes;

    public DenseRetrievalService(EmbeddingProfileVersionRepository profiles, List<EmbeddingPort> providers,
            DenseRetrievalRepository retrieval, SceneRepository scenes) {
        this.profiles = profiles;
        this.providers = List.copyOf(providers);
        this.retrieval = retrieval;
        this.scenes = scenes;
    }

    public List<DenseRetrievalResult> searchKnowledge(UUID roundId, UUID subSceneId, String query, int topK) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.length() < 2 || normalized.length() > 1_000) {
            throw new IllegalArgumentException("retrieval query must contain between 2 and 1000 characters");
        }
        if (scenes.findRound(roundId).filter(round -> round.subSceneId().equals(subSceneId)).isEmpty()) {
            throw new NotFoundException("round does not belong to sub-scene: " + roundId);
        }
        EmbeddingProfileVersion profile = profiles.findActive()
                .orElseThrow(() -> new ConflictException("no active embedding profile is configured"));
        EmbeddingPort provider = providers.stream()
                .filter(candidate -> profile.provider().equals(candidate.provider()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("active embedding Provider is unavailable"));
        List<Float> queryVector = provider.embedQuery(normalized, profile);
        if (queryVector.size() != profile.dimension()) {
            throw new IllegalStateException("embedding Provider returned an invalid query dimension");
        }
        return retrieval.searchKnowledge(roundId, subSceneId, profile, queryVector,
                Math.min(Math.max(topK, 1), 50));
    }
}
