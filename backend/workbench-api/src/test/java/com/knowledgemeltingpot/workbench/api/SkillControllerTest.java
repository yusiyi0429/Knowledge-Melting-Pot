package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.SkillService;
import com.knowledgemeltingpot.workbench.domain.Skill;
import com.knowledgemeltingpot.workbench.domain.SkillKind;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class SkillControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000302");

    @Test
    void templateCreationIsRestrictedToAdministratorsAtTheControllerBoundary() throws Exception {
        PreAuthorize authorization = SkillController.class.getMethod("createTemplate",
                SkillController.CreateSkillRequest.class, String.class,
                org.springframework.security.core.Authentication.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void forkAndVersionWritesAreRestrictedToOperatorsOrAdmins() throws Exception {
        PreAuthorize fork = SkillController.class.getMethod("forkInstance", UUID.class,
                SkillController.CreateInstanceRequest.class, String.class,
                org.springframework.security.core.Authentication.class)
                .getAnnotation(PreAuthorize.class);
        PreAuthorize version = SkillController.class.getMethod("createVersion", UUID.class,
                SkillController.CreateVersionRequest.class, String.class,
                org.springframework.security.core.Authentication.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(fork).isNotNull();
        assertThat(fork.value()).isEqualTo("hasAnyRole('OPERATOR','ADMIN')");
        assertThat(version).isNotNull();
        assertThat(version.value()).isEqualTo("hasAnyRole('OPERATOR','ADMIN')");
    }

    @Test
    void skillReadsAreRestrictedToOperatorsOrAdmins() throws Exception {
        PreAuthorize list = SkillController.class.getMethod("list", SkillKind.class, UUID.class)
                .getAnnotation(PreAuthorize.class);
        PreAuthorize get = SkillController.class.getMethod("get", UUID.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(list).isNotNull();
        assertThat(list.value()).isEqualTo("hasAnyRole('OPERATOR','ADMIN')");
        assertThat(get).isNotNull();
        assertThat(get.value()).isEqualTo("hasAnyRole('OPERATOR','ADMIN')");
    }

    @Test
    void summarySerializesOnlySafeResourceMetadata() throws Exception {
        Skill skill = new Skill(UUID.randomUUID(), "规则萃取", SkillKind.TEMPLATE, "", null, null, null,
                ACTOR_ID, NOW);
        SkillVersion version = new SkillVersion(UUID.randomUUID(), skill.id(), 1,
                "{\"executionMode\":\"RESOURCE_ONLY\",\"prompt\":{\"system\":\"只读\"}}",
                "a".repeat(64), ACTOR_ID, NOW);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(SkillController.SkillSummaryResponse.from(skill, version));

        assertThat(json)
                .contains("\"kind\":\"TEMPLATE\"")
                .contains("\"version\":1")
                .contains("RESOURCE_ONLY")
                .doesNotContain("\"script\"", "\"credential\"", "\"secret\"", "\"executable\"");
    }

    @Test
    void listAndDetailDelegationShape() {
        SkillService service = org.mockito.Mockito.mock(SkillService.class);
        Skill skill = new Skill(UUID.randomUUID(), "规则萃取", SkillKind.INSTANCE, "", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), ACTOR_ID, NOW);
        SkillVersion version = new SkillVersion(UUID.randomUUID(), skill.id(), 1,
                "{\"executionMode\":\"RESOURCE_ONLY\"}", "b".repeat(64), ACTOR_ID, NOW);
        org.mockito.Mockito.when(service.list(SkillKind.INSTANCE, null))
                .thenReturn(List.of(new SkillService.SkillWithVersion(skill, version)));
        org.mockito.Mockito.when(service.detail(skill.id()))
                .thenReturn(new SkillService.SkillDetail(skill, List.of(version)));
        SkillController controller = new SkillController(service,
                org.mockito.Mockito.mock(CurrentUser.class));

        var summary = controller.list(SkillKind.INSTANCE, null);
        var detail = controller.get(skill.id());

        assertThat(summary).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("INSTANCE");
            assertThat(item.sourceSkillId()).isNotNull();
            assertThat(item.sourceSkillVersionId()).isNotNull();
        });
        assertThat(detail.versions()).hasSize(1);
    }
}
