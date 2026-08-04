package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.IdempotencyRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SkillServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private final SkillRepository skills = mock(SkillRepository.class);
    private final SceneRepository scenes = mock(SceneRepository.class);
    private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
    private final SkillManifestValidator validator = new SkillManifestValidator(new ObjectMapper());
    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(skills, scenes, idempotency, validator,
                mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final String MANIFEST = "{\"schemaVersion\":\"1.0\",\"executionMode\":\"RESOURCE_ONLY\","
            + "\"resources\":[\"rules.json\"]}";

    @Test
    void createsTemplateWithFirstImmutableVersionAndForksWithCurrentActor() {
        when(skills.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skills.insertVersion(any(), any(), any(), any(), eq(ACTOR_ID), any()))
                .thenAnswer(invocation -> new SkillVersion(invocation.getArgument(1, UUID.class),
                        invocation.getArgument(0, Skill.class).id(), 1,
                        invocation.getArgument(2), invocation.getArgument(3), ACTOR_ID, NOW));

        SkillService.SkillCreation created = service.createTemplate("规则萃取", "模板说明",
                MANIFEST, "a".repeat(64), ACTOR_ID, null, "trace");

        assertThat(created.skill().kind()).isEqualTo(SkillKind.TEMPLATE);
        assertThat(created.skill().sceneId()).isNull();
        assertThat(created.skill().sourceSkillId()).isNull();
        assertThat(created.skill().sourceSkillVersionId()).isNull();
        assertThat(created.skill().createdBy()).isEqualTo(ACTOR_ID);
        assertThat(created.version().version()).isEqualTo(1);
    }

    @Test
    void rejectsManifestWithScripts() {
        assertThatThrownBy(() -> service.createTemplate("坏模板", null,
                "{\"executionMode\":\"RESOURCE_ONLY\",\"script\":\"evil.sh\"}", "a".repeat(64),
                ACTOR_ID, null, "trace"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forksInstanceOnlyFromTemplateWithSceneAndSourceVersion() {
        UUID templateId = UUID.randomUUID();
        UUID sceneId = UUID.randomUUID();
        Skill template = new Skill(templateId, "规则萃取", SkillKind.TEMPLATE, "", null, null, null,
                ACTOR_ID, NOW);
        SkillVersion source = new SkillVersion(UUID.randomUUID(), templateId, 2, MANIFEST,
                "b".repeat(64), ACTOR_ID, NOW);
        when(skills.findById(templateId)).thenReturn(Optional.of(template));
        when(scenes.findScene(sceneId)).thenReturn(Optional.of(new Scene(sceneId, "场景", "", NOW, NOW)));
        when(skills.findLatestVersion(templateId)).thenReturn(Optional.of(source));
        when(skills.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(skills.insertVersion(any(), any(), any(), any(), eq(ACTOR_ID), any()))
                .thenAnswer(invocation -> new SkillVersion(invocation.getArgument(1, UUID.class),
                        invocation.getArgument(0, Skill.class).id(), 1,
                        invocation.getArgument(2), invocation.getArgument(3), ACTOR_ID, NOW));

        SkillService.SkillCreation instance = service.forkInstance(templateId, sceneId, ACTOR_ID, null, "trace");

        assertThat(instance.skill().kind()).isEqualTo(SkillKind.INSTANCE);
        assertThat(instance.skill().sceneId()).isEqualTo(sceneId);
        assertThat(instance.skill().sourceSkillId()).isEqualTo(templateId);
        assertThat(instance.skill().sourceSkillVersionId()).isEqualTo(source.id());
        // The instance and its first version belong to the forking actor, never the template owner.
        assertThat(instance.skill().createdBy()).isEqualTo(ACTOR_ID);
        assertThat(instance.version().createdBy()).isEqualTo(ACTOR_ID);
        assertThat(instance.version().packageHash()).isEqualTo(source.packageHash());
    }

    @Test
    void rejectsForkingFromAnInstanceOrMissingScene() {
        UUID instanceId = UUID.randomUUID();
        Skill instance = new Skill(instanceId, "规则萃取", SkillKind.INSTANCE, "", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), ACTOR_ID, NOW);
        when(skills.findById(instanceId)).thenReturn(Optional.of(instance));
        when(scenes.findScene(any())).thenReturn(Optional.of(new Scene(UUID.randomUUID(), "场景", "", NOW, NOW)));

        assertThatThrownBy(() -> service.forkInstance(instanceId, UUID.randomUUID(), ACTOR_ID, null, "trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEMPLATE");
    }

    @Test
    void onlyInstanceSkillsReceiveNewVersions() {
        UUID templateId = UUID.randomUUID();
        Skill template = new Skill(templateId, "规则萃取", SkillKind.TEMPLATE, "", null, null, null,
                ACTOR_ID, NOW);
        when(skills.findById(templateId)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.createVersion(templateId, MANIFEST, "a".repeat(64),
                ACTOR_ID, null, "trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INSTANCE");
    }

    @Test
    void listsWithLatestVersionAndFiltersByKind() {
        UUID templateId = UUID.randomUUID();
        Skill template = new Skill(templateId, "规则萃取", SkillKind.TEMPLATE, "", null, null, null,
                ACTOR_ID, NOW);
        SkillVersion version = new SkillVersion(UUID.randomUUID(), templateId, 1, MANIFEST,
                "a".repeat(64), ACTOR_ID, NOW);
        when(skills.findSkills(SkillKind.TEMPLATE, null)).thenReturn(List.of(template));
        when(skills.findLatestVersion(templateId)).thenReturn(Optional.of(version));

        List<SkillService.SkillWithVersion> result = service.list(SkillKind.TEMPLATE, null);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.skill().id()).isEqualTo(templateId);
            assertThat(item.latest().version()).isEqualTo(1);
        });
        verify(skills).findSkills(eq(SkillKind.TEMPLATE), eq(null));
    }

    @Test
    void idempotentCreateReturnsTheSameSkill() {
        UUID skillId = UUID.randomUUID();
        Skill template = new Skill(skillId, "规则萃取", SkillKind.TEMPLATE, "", null, null, null,
                ACTOR_ID, NOW);
        SkillVersion version = new SkillVersion(UUID.randomUUID(), skillId, 1, MANIFEST,
                "a".repeat(64), ACTOR_ID, NOW);
        String normalized = validator.validate(MANIFEST);
        String hash = com.knowledgemeltingpot.workbench.application.service.Hashes.sha256(
                String.join("\n", "规则萃取", "", normalized, "a".repeat(64), ""));
        when(idempotency.find(Mockito.anyString(), eq("key-001"))).thenReturn(Optional.of(
                new com.knowledgemeltingpot.workbench.application.port.IdempotencyRecord(
                        "skill-write:" + ACTOR_ID, "key-001", hash, "SKILL", skillId, NOW, NOW)));
        when(skills.findById(skillId)).thenReturn(Optional.of(template));
        when(skills.findLatestVersion(skillId)).thenReturn(Optional.of(version));

        SkillService.SkillCreation replay = service.createTemplate("规则萃取", "",
                MANIFEST, "a".repeat(64), ACTOR_ID, "key-001", "trace");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.skill().id()).isEqualTo(skillId);
    }

    @Test
    void missingSkillThrowsNotFound() {
        when(skills.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
