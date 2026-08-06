package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.AssetRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SceneService {
    private final SceneRepository sceneRepository;
    private final AssetRepository assetRepository;
    private final AuditService auditService;
    private final Clock clock;

    public SceneService(SceneRepository sceneRepository, AssetRepository assetRepository,
            AuditService auditService, Clock clock) {
        this.sceneRepository = sceneRepository;
        this.assetRepository = assetRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Scene create(String name, String description, UUID actorId, String traceId) {
        Instant now = Instant.now(clock);
        Scene scene = sceneRepository.save(new Scene(UUID.randomUUID(), name, description, now, now));
        auditService.record(actorId, "SCENE_CREATED", "SCENE", scene.id(), Map.of("name", scene.name()), traceId);
        return scene;
    }

    @Transactional(readOnly = true)
    public List<Scene> list() {
        return sceneRepository.findAllScenes();
    }

    @Transactional(readOnly = true)
    public Scene get(UUID sceneId) {
        return sceneRepository.findScene(sceneId)
                .orElseThrow(() -> new NotFoundException("scene not found: " + sceneId));
    }

    @Transactional
    public Scene update(UUID sceneId, String name, String description, UUID actorId, String traceId) {
        Scene existing = get(sceneId);
        Scene updated = sceneRepository.save(existing.update(name, description, Instant.now(clock)));
        auditService.record(actorId, "SCENE_UPDATED", "SCENE", sceneId, Map.of("name", updated.name()), traceId);
        return updated;
    }

    @Transactional
    public void delete(UUID sceneId, UUID actorId, String traceId) {
        Scene scene = get(sceneId);
        Instant archivedAt = Instant.now(clock);
        if (!sceneRepository.archiveScene(sceneId, actorId, archivedAt)) {
            throw new NotFoundException("scene not found: " + sceneId);
        }
        auditService.record(actorId, "SCENE_ARCHIVED", "SCENE", sceneId,
                Map.of("name", scene.name(), "archivedAt", archivedAt), traceId);
    }

    @Transactional
    public SubScene createSubScene(UUID sceneId, String name, String description, UUID actorId, String traceId) {
        get(sceneId);
        Instant now = Instant.now(clock);
        SubScene subScene = sceneRepository.save(
                new SubScene(UUID.randomUUID(), sceneId, name, description, now, now));
        assetRepository.ensurePlaceholders(subScene.id(), now);
        sceneRepository.createNextRound(subScene.id(), UUID.randomUUID(), now);
        auditService.record(actorId, "SUB_SCENE_CREATED", "SUB_SCENE", subScene.id(),
                Map.of("sceneId", sceneId, "name", subScene.name()), traceId);
        return subScene;
    }

    @Transactional(readOnly = true)
    public SubScene getSubScene(UUID subSceneId) {
        return sceneRepository.findSubScene(subSceneId)
                .orElseThrow(() -> new NotFoundException("sub-scene not found: " + subSceneId));
    }

    @Transactional(readOnly = true)
    public List<SubScene> listSubScenes(UUID sceneId) {
        get(sceneId);
        return sceneRepository.findSubScenes(sceneId);
    }

    @Transactional
    public ExtractionRound createRound(UUID sceneId, UUID subSceneId, UUID actorId, String traceId) {
        get(sceneId);
        SubScene subScene = getSubScene(subSceneId);
        if (!subScene.sceneId().equals(sceneId)) {
            throw new IllegalArgumentException("sub-scene does not belong to the requested scene");
        }
        ExtractionRound round = sceneRepository.createNextRound(subSceneId, UUID.randomUUID(), Instant.now(clock));
        auditService.record(actorId, "EXTRACTION_ROUND_CREATED", "EXTRACTION_ROUND", round.id(),
                Map.of("sceneId", sceneId, "subSceneId", subSceneId, "roundNumber", round.roundNumber()), traceId);
        return round;
    }

    @Transactional(readOnly = true)
    public List<ExtractionRound> listRounds(UUID sceneId) {
        get(sceneId);
        return sceneRepository.findRoundsByScene(sceneId);
    }
}
