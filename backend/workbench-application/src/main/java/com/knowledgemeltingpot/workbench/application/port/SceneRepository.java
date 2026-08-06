package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SceneRepository {
    Scene save(Scene scene);

    Optional<Scene> findScene(UUID id);

    List<Scene> findAllScenes();

    boolean archiveScene(UUID id, UUID actorId, Instant archivedAt);

    SubScene save(SubScene subScene);

    Optional<SubScene> findSubScene(UUID id);

    List<SubScene> findSubScenes(UUID sceneId);

    Optional<ExtractionRound> findRound(UUID id);

    ExtractionRound createNextRound(UUID subSceneId, UUID roundId, Instant now);

    List<ExtractionRound> findRoundsByScene(UUID sceneId);
}
