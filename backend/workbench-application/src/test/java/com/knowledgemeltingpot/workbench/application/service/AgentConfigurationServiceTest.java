package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.PreconditionFailedException;
import com.knowledgemeltingpot.workbench.application.port.AgentMountRepository;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.SceneRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.domain.AgentMountScope;
import com.knowledgemeltingpot.workbench.domain.AgentMountVersion;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.AgentRoleTemplateVersion;
import com.knowledgemeltingpot.workbench.domain.Scene;
import com.knowledgemeltingpot.workbench.domain.SubScene;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentConfigurationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private final UUID sceneId = UUID.randomUUID();
    private final UUID subSceneId = UUID.randomUUID();
    private AgentMountRepository mounts;
    private SceneRepository scenes;
    private ModelConnectionRepository models;
    private SkillRepository skills;
    private AgentConfigurationService service;

    @BeforeEach
    void setUp() {
        mounts = mock(AgentMountRepository.class);
        scenes = mock(SceneRepository.class);
        models = mock(ModelConnectionRepository.class);
        skills = mock(SkillRepository.class);
        when(scenes.findScene(sceneId)).thenReturn(Optional.of(new Scene(sceneId, "场景", "", NOW, NOW)));
        when(scenes.findSubScene(subSceneId)).thenReturn(Optional.of(
                new SubScene(subSceneId, sceneId, "子场景", "", NOW, NOW)));
        when(mounts.findLatestTemplates()).thenReturn(List.of(template(AgentRole.KNOWLEDGE_EXTRACTOR)));
        service = new AgentConfigurationService(mounts, scenes, models, skills, mock(AuditService.class),
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void resolvesFieldsIndependentlyAcrossGlobalSceneAndSubScene() {
        UUID globalModel = UUID.randomUUID();
        UUID sceneSkill = UUID.randomUUID();
        UUID subSceneModel = UUID.randomUUID();
        when(mounts.findLatest(AgentMountScope.GLOBAL, null)).thenReturn(List.of(
                mount(AgentMountScope.GLOBAL, null, 1, true, globalModel, null, null)));
        when(mounts.findLatest(AgentMountScope.SCENE, sceneId)).thenReturn(List.of(
                mount(AgentMountScope.SCENE, sceneId, 1, null, null, sceneSkill, "{\"mode\":\"safe\"}")));
        when(mounts.findLatest(AgentMountScope.SUB_SCENE, subSceneId)).thenReturn(List.of(
                mount(AgentMountScope.SUB_SCENE, subSceneId, 1, null, subSceneModel, null, null)));

        var resolved = service.resolve(sceneId, subSceneId).stream()
                .filter(item -> item.role() == AgentRole.KNOWLEDGE_EXTRACTOR).findFirst().orElseThrow();

        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.modelConfigVersionId()).isEqualTo(subSceneModel);
        assertThat(resolved.skillVersionId()).isEqualTo(sceneSkill);
        assertThat(resolved.enabledSource()).isEqualTo(AgentMountScope.GLOBAL);
        assertThat(resolved.modelSource()).isEqualTo(AgentMountScope.SUB_SCENE);
        assertThat(resolved.skillSource()).isEqualTo(AgentMountScope.SCENE);
        assertThat(resolved.optionsJson()).contains("safe");
        assertThat(resolved.lineage()).hasSize(3);
        assertThat(resolved.effectiveHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void appendRequiresTheCurrentScopeEtagAndCreatesAnImmutableHead() {
        when(mounts.findLatest(AgentMountScope.SCENE, sceneId)).thenReturn(List.of());
        when(mounts.findLatest(AgentMountScope.SCENE, sceneId, AgentRole.KNOWLEDGE_EXTRACTOR))
                .thenReturn(Optional.empty());
        when(mounts.insert(any(), eq(sceneId))).thenAnswer(invocation -> invocation.getArgument(0));
        String emptyEtag = Hashes.sha256("[]");

        service.append(AgentMountScope.SCENE, sceneId,
                new AgentConfigurationService.AgentMountDraft(AgentRole.KNOWLEDGE_EXTRACTOR, true,
                        null, null, null), emptyEtag, ACTOR, "trace");

        verify(mounts).lockScope(AgentMountScope.SCENE, sceneId);
        verify(mounts).insert(any(AgentMountVersion.class), eq(sceneId));
        assertThatThrownBy(() -> service.append(AgentMountScope.SCENE, sceneId,
                new AgentConfigurationService.AgentMountDraft(AgentRole.KNOWLEDGE_EXTRACTOR, true,
                        null, null, null), "0".repeat(64), ACTOR, "trace"))
                .isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void rejectsSecretsAndExecutableFieldsInOptions() {
        assertThatThrownBy(() -> service.previewImport(AgentMountScope.SCENE, sceneId, List.of(
                new AgentConfigurationService.AgentMountDraft(AgentRole.KNOWLEDGE_EXTRACTOR, true,
                        null, null, Map.of("nested", Map.of("apiKey", "never-store-this")))), ACTOR, "trace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secrets or executable");
    }

    @Test
    void refusesToApplyAValidPreviewAfterTheScopeChanged() {
        when(mounts.findLatest(AgentMountScope.SCENE, sceneId))
                .thenReturn(List.of(), List.of(mount(AgentMountScope.SCENE, sceneId, 1, true,
                        null, null, null)));
        var preview = service.previewImport(AgentMountScope.SCENE, sceneId, List.of(
                new AgentConfigurationService.AgentMountDraft(AgentRole.KNOWLEDGE_EXTRACTOR, true,
                        null, null, null)), ACTOR, "trace");
        when(mounts.findImport(preview.id())).thenReturn(Optional.of(preview));

        assertThatThrownBy(() -> service.applyImport(preview.id(), preview.manifestHash(), ACTOR, "trace"))
                .isInstanceOf(PreconditionFailedException.class)
                .hasMessageContaining("changed after");
        verify(mounts, never()).markImportApplied(any(), any(), any());
    }

    private AgentRoleTemplateVersion template(AgentRole role) {
        return new AgentRoleTemplateVersion(UUID.randomUUID(), role, 1, role.displayName(), role.description(),
                "{}", "a".repeat(64), NOW);
    }

    private AgentMountVersion mount(AgentMountScope scope, UUID scopeId, int version, Boolean enabled,
            UUID model, UUID skill, String options) {
        return new AgentMountVersion(UUID.randomUUID(), AgentRole.KNOWLEDGE_EXTRACTOR, scope, scopeId, version,
                UUID.randomUUID(), enabled, model, skill, options, UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""), ACTOR, NOW);
    }
}
