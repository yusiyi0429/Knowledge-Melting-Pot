package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.DenseRetrievalService;
import com.knowledgemeltingpot.workbench.application.service.EmbeddingProfileService;
import com.knowledgemeltingpot.workbench.domain.ChunkLocator;
import com.knowledgemeltingpot.workbench.domain.DenseRetrievalResult;
import com.knowledgemeltingpot.workbench.domain.EmbeddingProfileVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
public class EmbeddingController {
    private final EmbeddingProfileService profiles;
    private final DenseRetrievalService retrieval;
    private final CurrentUser currentUser;

    public EmbeddingController(EmbeddingProfileService profiles, DenseRetrievalService retrieval,
            CurrentUser currentUser) {
        this.profiles = profiles;
        this.retrieval = retrieval;
        this.currentUser = currentUser;
    }

    @GetMapping("/embedding-profiles")
    @PreAuthorize("hasRole('ADMIN')")
    public List<EmbeddingProfileResponse> listProfiles() {
        return profiles.list().stream().map(EmbeddingProfileResponse::from).toList();
    }

    @PostMapping("/embedding-profiles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EmbeddingProfileResponse> createProfile(
            @Valid @RequestBody CreateEmbeddingProfileRequest body, Authentication authentication) {
        EmbeddingProfileVersion profile = profiles.createAndActivate(body.modelConnectionId(), body.modelId(),
                body.dimension(), body.profileVersion(), body.normalization(), body.distanceFunction(),
                currentUser.id(authentication), RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/embedding-profiles/" + profile.id()))
                .body(EmbeddingProfileResponse.from(profile));
    }

    @GetMapping("/retrieval/chunks")
    public List<DenseRetrievalResponse> retrieve(@RequestParam UUID roundId,
            @RequestParam UUID subSceneId, @RequestParam("q") String query,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int topK) {
        return retrieval.searchKnowledge(roundId, subSceneId, query, topK).stream()
                .map(DenseRetrievalResponse::from)
                .toList();
    }

    public record CreateEmbeddingProfileRequest(
            @NotNull UUID modelConnectionId,
            @NotBlank @Size(max = 200) String modelId,
            @Min(1) @Max(2000) int dimension,
            @NotBlank @Size(max = 100) String profileVersion,
            @NotBlank @Pattern(regexp = "NONE|L2") String normalization,
            @NotBlank @Pattern(regexp = "COSINE|L2") String distanceFunction) {
    }

    public record EmbeddingProfileResponse(
            UUID id,
            UUID modelConnectionId,
            String provider,
            String modelId,
            int dimension,
            String profileVersion,
            String normalization,
            String distanceFunction,
            boolean active,
            Instant createdAt) {
        static EmbeddingProfileResponse from(EmbeddingProfileVersion profile) {
            return new EmbeddingProfileResponse(profile.id(), profile.modelConnectionId(), profile.provider(),
                    profile.modelId(), profile.dimension(), profile.profileVersion(), profile.normalization(),
                    profile.distanceFunction(), profile.active(), profile.createdAt());
        }
    }

    public record DenseRetrievalResponse(
            UUID chunkId,
            UUID materialId,
            String sourceRefCode,
            String locatorType,
            Integer page,
            Integer paragraph,
            Integer table,
            String sheet,
            Integer rowStart,
            Integer rowEnd,
            Integer colStart,
            Integer colEnd,
            Integer lineStart,
            Integer lineEnd,
            String excerpt,
            double score) {
        static DenseRetrievalResponse from(DenseRetrievalResult result) {
            ChunkLocator locator = result.locator();
            String excerpt = result.content().length() <= 500
                    ? result.content()
                    : result.content().substring(0, 500) + "…";
            return new DenseRetrievalResponse(result.chunkId(), result.materialId(), result.sourceRefCode(),
                    locator.type().name(), locator.page(), locator.paragraph(), locator.table(), locator.sheet(),
                    locator.rowStart(), locator.rowEnd(), locator.colStart(), locator.colEnd(),
                    locator.lineStart(), locator.lineEnd(), excerpt, result.score());
        }
    }
}
