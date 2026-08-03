package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.SceneService;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import com.knowledgemeltingpot.workbench.domain.ExtractionRound;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/scenes")
public class SceneController {
    private final SceneService sceneService;
    private final CurrentUser currentUser;

    public SceneController(SceneService sceneService, CurrentUser currentUser) {
        this.sceneService = sceneService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ScenePageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        List<Scene> allScenes = sceneService.list();
        int from = (int) Math.min((long) page * size, allScenes.size());
        int to = Math.min(from + size, allScenes.size());
        return new ScenePageResponse(allScenes.subList(from, to), page, size, allScenes.size());
    }

    @PostMapping
    public ResponseEntity<Scene> create(@Valid @RequestBody SceneRequest body, Authentication authentication) {
        Scene scene = sceneService.create(body.name(), body.description(), currentUser.id(authentication),
                RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/scenes/" + scene.id())).body(scene);
    }

    @GetMapping("/{sceneId}")
    public Scene get(@PathVariable UUID sceneId) {
        return sceneService.get(sceneId);
    }

    @PutMapping("/{sceneId}")
    public Scene update(@PathVariable UUID sceneId, @Valid @RequestBody SceneRequest body,
            Authentication authentication) {
        return sceneService.update(sceneId, body.name(), body.description(), currentUser.id(authentication),
                RequestIdFilter.currentTraceId());
    }

    @DeleteMapping("/{sceneId}")
    public ResponseEntity<Void> delete(@PathVariable UUID sceneId, Authentication authentication) {
        sceneService.delete(sceneId, currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{sceneId}/subscenes")
    public List<SubScene> listSubScenes(@PathVariable UUID sceneId) {
        return sceneService.listSubScenes(sceneId);
    }

    @PostMapping("/{sceneId}/subscenes")
    public ResponseEntity<SubScene> createSubScene(@PathVariable UUID sceneId,
            @Valid @RequestBody SceneRequest body, Authentication authentication) {
        SubScene subScene = sceneService.createSubScene(sceneId, body.name(), body.description(),
                currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/subscenes/" + subScene.id())).body(subScene);
    }

    @GetMapping("/{sceneId}/rounds")
    public List<ExtractionRound> listRounds(@PathVariable UUID sceneId) {
        return sceneService.listRounds(sceneId);
    }

    @PostMapping("/{sceneId}/rounds")
    public ResponseEntity<ExtractionRound> createRound(@PathVariable UUID sceneId,
            @Valid @RequestBody CreateRoundRequest body, Authentication authentication) {
        ExtractionRound round = sceneService.createRound(sceneId, body.subSceneId(),
                currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/scenes/" + sceneId + "/rounds/" + round.id()))
                .body(round);
    }

    public record SceneRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 10_000) String description) {
    }

    public record CreateRoundRequest(@jakarta.validation.constraints.NotNull UUID subSceneId) {
    }

    public record ScenePageResponse(List<Scene> items, int page, int size, long total) {
    }
}
