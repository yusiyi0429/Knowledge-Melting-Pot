package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgemeltingpot.workbench.application.port.ModelEndpointRuleRepository;
import com.knowledgemeltingpot.workbench.domain.ModelEndpointRule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelEndpointRuleServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Test
    void extendsAnExistingExactHostWithoutDroppingItsCurrentPolicy() {
        UUID ruleId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ModelEndpointRule existing = new ModelEndpointRule(ruleId, "llm.bank.local", Set.of(443),
                false, false, actorId, actorId, NOW.minusSeconds(60), NOW.minusSeconds(60));
        ModelEndpointRuleRepository repository = mock(ModelEndpointRuleRepository.class);
        when(repository.findByNormalizedHost("llm.bank.local")).thenReturn(Optional.of(existing));
        when(repository.findById(ruleId)).thenReturn(Optional.of(existing));
        when(repository.save(any(ModelEndpointRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ModelEndpointRuleService service = new ModelEndpointRuleService(repository, mock(AuditService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        ModelEndpointRule result = service.ensureHost("LLM.BANK.LOCAL", 8000, true, true,
                actorId, "trace-1");

        assertThat(result.id()).isEqualTo(ruleId);
        assertThat(result.allowedPorts()).containsExactlyInAnyOrder(443, 8000);
        assertThat(result.allowHttp()).isTrue();
        assertThat(result.allowPrivateAddresses()).isTrue();
        assertThat(result.updatedAt()).isEqualTo(NOW);
    }
}
