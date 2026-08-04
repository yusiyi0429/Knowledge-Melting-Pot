package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.http.RequestIdFilter;
import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.SkillService;
import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {
    private final SkillService skillService;
    private final CurrentUser currentUser;

    public SkillController(SkillService skillService, CurrentUser currentUser) {
        this.skillService = skillService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public List<SkillSummaryResponse> list(@RequestParam(required = false) SkillKind kind,
            @RequestParam(required = false) UUID sceneId) {
        return skillService.list(kind, sceneId).stream()
                .map(item -> SkillSummaryResponse.from(item.skill(), item.latest()))
                .toList();
    }

    @GetMapping("/{skillId}")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public SkillDetailResponse get(@PathVariable UUID skillId) {
        SkillService.SkillDetail detail = skillService.detail(skillId);
        return new SkillDetailResponse(detail.skill().id(), detail.skill().name(), detail.skill().kind().name(),
                detail.skill().description(), detail.skill().sceneId(), detail.skill().sourceSkillId(),
                detail.skill().sourceSkillVersionId(), detail.skill().createdAt(),
                detail.versions().stream().map(SkillVersionResponse::from).toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SkillSummaryResponse> createTemplate(@Valid @RequestBody CreateSkillRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        SkillService.SkillCreation created = skillService.createTemplate(body.name(), body.description(),
                body.manifest(), body.packageHash(), currentUser.id(authentication), idempotencyKey,
                RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/skills/" + created.skill().id()))
                .header("X-Idempotent-Replay", Boolean.toString(created.replayed()))
                .body(SkillSummaryResponse.from(created.skill(), created.version()));
    }

    @PostMapping("/{skillId}/instances")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public ResponseEntity<SkillSummaryResponse> forkInstance(@PathVariable UUID skillId,
            @Valid @RequestBody CreateInstanceRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        SkillService.SkillCreation created = skillService.forkInstance(skillId, body.sceneId(),
                currentUser.id(authentication), idempotencyKey, RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/skills/" + created.skill().id()))
                .header("X-Idempotent-Replay", Boolean.toString(created.replayed()))
                .body(SkillSummaryResponse.from(created.skill(), created.version()));
    }

    @PostMapping("/{skillId}/versions")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public ResponseEntity<SkillVersionResponse> createVersion(@PathVariable UUID skillId,
            @Valid @RequestBody CreateVersionRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        SkillService.SkillVersionCreation created = skillService.createVersion(skillId, body.manifest(),
                body.packageHash(), currentUser.id(authentication), idempotencyKey,
                RequestIdFilter.currentTraceId());
        return ResponseEntity.created(URI.create("/api/v1/skills/" + skillId + "/versions/" + created.version().id()))
                .header("X-Idempotent-Replay", Boolean.toString(created.replayed()))
                .body(SkillVersionResponse.from(created.version()));
    }

    public record CreateSkillRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank String manifest,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String packageHash) {
    }

    public record CreateInstanceRequest(@NotNull UUID sceneId) {
    }

    public record CreateVersionRequest(
            @NotBlank String manifest,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String packageHash) {
    }

    public record SkillSummaryResponse(
            UUID id,
            String name,
            String kind,
            String description,
            UUID sceneId,
            UUID sourceSkillId,
            UUID sourceSkillVersionId,
            Integer version,
            String packageHash,
            String manifestJson,
            Instant createdAt) {

        static SkillSummaryResponse from(Skill skill, SkillVersion latest) {
            return new SkillSummaryResponse(skill.id(), skill.name(), skill.kind().name(), skill.description(),
                    skill.sceneId(), skill.sourceSkillId(), skill.sourceSkillVersionId(),
                    latest == null ? null : latest.version(),
                    latest == null ? null : latest.packageHash(),
                    latest == null ? null : latest.manifestJson(),
                    skill.createdAt());
        }
    }

    public record SkillVersionResponse(
            UUID id,
            UUID skillId,
            int version,
            String manifestJson,
            String packageHash,
            UUID createdBy,
            Instant createdAt) {

        static SkillVersionResponse from(SkillVersion version) {
            return new SkillVersionResponse(version.id(), version.skillId(), version.version(),
                    version.manifestJson(), version.packageHash(), version.createdBy(), version.createdAt());
        }
    }

    public record SkillDetailResponse(
            UUID id,
            String name,
            String kind,
            String description,
            UUID sceneId,
            UUID sourceSkillId,
            UUID sourceSkillVersionId,
            Instant createdAt,
            List<SkillVersionResponse> versions) {
    }
}
