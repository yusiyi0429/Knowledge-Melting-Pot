package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ExplorationCandidate;
import com.knowledgemeltingpot.workbench.domain.ExplorationSession;
import com.knowledgemeltingpot.workbench.domain.ExplorationStatus;
import com.knowledgemeltingpot.workbench.domain.Material;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExplorationRepository {
    ExplorationSession insert(ExplorationSession session);

    Optional<ExplorationSession> find(UUID id);

    Optional<ExplorationSession> lock(UUID id);

    List<ExplorationSession> findRecent(int limit);

    boolean linkMaterial(UUID sessionId, UUID materialId, Instant createdAt);

    List<Material> findMaterials(UUID sessionId);

    boolean freezeRun(UUID sessionId, int expectedVersion, UUID jobId, UUID modelConfigVersionId,
            UUID skillVersionId, UUID roleConfigVersionId, String effectiveConfigHash, Instant updatedAt);

    boolean completeAnalysis(UUID sessionId, List<ExplorationCandidate> candidates, Instant updatedAt);

    List<ExplorationCandidate> findCandidates(UUID sessionId);

    Optional<ExplorationCandidate> findCandidate(UUID sessionId, UUID candidateId);

    boolean transition(UUID sessionId, ExplorationStatus expected, ExplorationStatus target, Instant updatedAt);

    boolean accept(UUID sessionId, int expectedVersion, UUID candidateId, UUID sceneId, UUID subSceneId,
            UUID roundId, UUID actorId, Instant acceptedAt);

    Optional<ExplorationAcceptance> findAcceptance(UUID sessionId);

    record ExplorationAcceptance(UUID sessionId, UUID candidateId, UUID sceneId, UUID subSceneId,
            UUID roundId, UUID acceptedBy, Instant acceptedAt) { }
}
