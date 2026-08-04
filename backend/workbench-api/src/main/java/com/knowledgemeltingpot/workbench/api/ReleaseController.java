package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.service.ReleaseCommand;
import com.knowledgemeltingpot.workbench.application.service.ReleaseService;
import com.knowledgemeltingpot.workbench.application.service.ReleaseValidation;
import com.knowledgemeltingpot.workbench.domain.Release;
import com.knowledgemeltingpot.workbench.domain.ReleaseCoverage;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReleaseController {
    private final ReleaseService releaseService;
    private final CurrentUser currentUser;

    public ReleaseController(ReleaseService releaseService, CurrentUser currentUser) {
        this.releaseService = releaseService;
        this.currentUser = currentUser;
    }

    @PostMapping("/scenes/{sceneId}/release-validations")
    public ReleaseValidation validate(@PathVariable UUID sceneId,
            @Valid @RequestBody CreateReleaseRequest body) {
        return releaseService.validate(sceneId, body.toCommand());
    }

    @PostMapping("/scenes/{sceneId}/releases")
    public ResponseEntity<ReleaseResponse> publish(@PathVariable UUID sceneId,
            @Valid @RequestBody CreateReleaseRequest body,
            Authentication authentication) {
        Release release = releaseService.publish(sceneId, body.toCommand(), currentUser.id(authentication),
                RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/releases/" + release.id()))
                .body(ReleaseResponse.from(release));
    }

    @GetMapping("/releases/{releaseId}")
    public ReleaseResponse get(@PathVariable UUID releaseId) {
        return ReleaseResponse.from(releaseService.get(releaseId));
    }

    @GetMapping("/scenes/{sceneId}/releases/latest")
    public ReleaseResponse latest(@PathVariable UUID sceneId) {
        return ReleaseResponse.from(releaseService.findLatestPublished(sceneId)
                .orElseThrow(() -> new NotFoundException("scene has no published release")));
    }

    @GetMapping(value = "/releases/{releaseId}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> manifest(@PathVariable UUID releaseId) {
        Release release = releaseService.get(releaseId);
        return ResponseEntity.ok()
                .eTag("\"sha256-" + release.manifestHash() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(release.manifestJson());
    }

    public record CreateReleaseRequest(
            @NotBlank @Size(max = 100)
            @Pattern(regexp = "^v[0-9]+\\.[0-9]+(?:\\.[0-9]+)?$", message = "must be a semantic release tag")
            String tag,
            @NotEmpty List<@NotNull UUID> selectedSubSceneIds,
            @NotBlank @Size(max = 2000) String note,
            @AssertTrue(message = "must be true after secondary confirmation") boolean confirmed,
            UUID expectedBaseReleaseId) {

        ReleaseCommand toCommand() {
            return new ReleaseCommand(tag, selectedSubSceneIds, note, confirmed, expectedBaseReleaseId);
        }
    }

    public record ReleaseResponse(
            UUID id,
            UUID sceneId,
            String tag,
            ReleaseCoverage coverage,
            String note,
            UUID previousReleaseId,
            String manifestSha256,
            Instant createdAt) {

        static ReleaseResponse from(Release release) {
            return new ReleaseResponse(release.id(), release.sceneId(), release.tag(), release.coverage(),
                    release.note(), release.previousReleaseId(), release.manifestHash(), release.createdAt());
        }
    }
}
