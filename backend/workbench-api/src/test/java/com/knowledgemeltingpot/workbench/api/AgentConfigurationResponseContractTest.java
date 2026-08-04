package com.knowledgemeltingpot.workbench.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.service.AgentConfigurationService.EffectiveAgentConfiguration;
import com.knowledgemeltingpot.workbench.domain.AgentRole;
import com.knowledgemeltingpot.workbench.domain.ConfigurationImportPreview;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentConfigurationResponseContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void derivedConfigurationAndImportStatesAreSerializedAsPublicProperties() {
        EffectiveAgentConfiguration effective = new EffectiveAgentConfiguration(
                AgentRole.KNOWLEDGE_EXTRACTOR, "知识萃取智能体", "环节二", true,
                UUID.randomUUID(), UUID.randomUUID(), "{}", "a".repeat(64), null,
                null, null, null, "TEMPLATE", List.of());
        ConfigurationImportPreview preview = new ConfigurationImportPreview(
                UUID.randomUUID(), "1.0", com.knowledgemeltingpot.workbench.domain.AgentMountScope.GLOBAL,
                null, null, "b".repeat(64), "{}", "c".repeat(64), "[]", UUID.randomUUID(),
                Instant.parse("2026-08-04T00:00:00Z"), null, null);

        assertThat(objectMapper.valueToTree(effective).path("configured").asBoolean()).isTrue();
        assertThat(objectMapper.valueToTree(preview).path("applied").asBoolean()).isFalse();
    }
}
