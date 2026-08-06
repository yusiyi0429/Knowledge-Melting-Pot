package com.knowledgemeltingpot.workbench.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.UserRepository;
import com.knowledgemeltingpot.workbench.application.service.SkillService;
import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.UserAccount;
import com.knowledgemeltingpot.workbench.domain.UserRole;
import com.knowledgemeltingpot.workbench.domain.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultSkillBootstrapTest {
    @Mock
    private SkillService skillService;
    @Mock
    private UserRepository userRepository;

    @Test
    void createsSafeTemplateForEveryAgentRole() {
        UserAccount actor = actor();
        when(skillService.list(SkillKind.TEMPLATE, null)).thenReturn(List.of());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(actor));

        new DefaultSkillBootstrap(skillService, userRepository, "admin").createDefaultSkills();

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> manifests = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(skillService, times(7)).createTemplate(names.capture(), any(), manifests.capture(),
                hashes.capture(), eq(actor.id()), any(), eq("bootstrap-agent-skills"));
        assertThat(names.getAllValues()).containsExactly(
                "场景探索基础模板", "知识萃取基础模板", "冲突检测与对齐基础模板", "规则库生成基础模板",
                "研判流程生成基础模板", "Skill 打包基础模板", "QA 与评测基础模板");
        assertThat(manifests.getAllValues()).allSatisfy(manifest -> assertThat(manifest)
                .contains("\"executionMode\": \"RESOURCE_ONLY\"")
                .contains("\"agentRole\"")
                .doesNotContain("\"script\"", "\"command\""));
        assertThat(hashes.getAllValues()).allSatisfy(hash -> assertThat(hash).matches("[0-9a-f]{64}"));
    }

    @Test
    void createsOnlyTemplatesThatAreMissing() {
        UserAccount actor = actor();
        Skill existing = new Skill(UUID.randomUUID(), "知识萃取基础模板", SkillKind.TEMPLATE, "existing",
                null, null, null, actor.id(), actor.createdAt());
        when(skillService.list(SkillKind.TEMPLATE, null))
                .thenReturn(List.of(new SkillService.SkillWithVersion(existing, null)));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(actor));

        new DefaultSkillBootstrap(skillService, userRepository, "admin").createDefaultSkills();

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(skillService, times(6)).createTemplate(names.capture(), any(), any(), any(), eq(actor.id()), any(),
                eq("bootstrap-agent-skills"));
        assertThat(names.getAllValues()).doesNotContain("知识萃取基础模板");
    }

    @Test
    void doesNothingWhenAllTemplatesExist() {
        UserAccount actor = actor();
        List<SkillService.SkillWithVersion> existing = List.of(
                existing("场景探索基础模板", actor), existing("知识萃取基础模板", actor),
                existing("冲突检测与对齐基础模板", actor), existing("规则库生成基础模板", actor),
                existing("研判流程生成基础模板", actor), existing("Skill 打包基础模板", actor),
                existing("QA 与评测基础模板", actor));
        when(skillService.list(SkillKind.TEMPLATE, null)).thenReturn(existing);

        new DefaultSkillBootstrap(skillService, userRepository, "admin").createDefaultSkills();

        verify(userRepository, never()).findByUsername(any());
        verify(skillService, never()).createTemplate(any(), any(), any(), any(), any(), any(), any());
    }

    private static SkillService.SkillWithVersion existing(String name, UserAccount actor) {
        return new SkillService.SkillWithVersion(new Skill(UUID.randomUUID(), name, SkillKind.TEMPLATE, "existing",
                null, null, null, actor.id(), actor.createdAt()), null);
    }

    private static UserAccount actor() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        return new UserAccount(UUID.randomUUID(), "admin", "管理员", "{bcrypt}hash", UserStatus.ACTIVE,
                Set.of(UserRole.ADMIN), false, now, now);
    }
}
