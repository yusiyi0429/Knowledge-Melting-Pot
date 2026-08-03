package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface SceneRepository {
    Scene save(Scene scene);

    Optional<Scene> findScene(UUID id);

    List<Scene> findAllScenes();

    boolean deleteScene(UUID id);

    SubScene save(SubScene subScene);

    Optional<SubScene> findSubScene(UUID id);

    List<SubScene> findSubScenes(UUID sceneId);

    Optional<ExtractionRound> findRound(UUID id);

    ExtractionRound createNextRound(UUID subSceneId, UUID roundId, Instant now);

    List<ExtractionRound> findRoundsByScene(UUID sceneId);
}
