package com.knowledgemeltingpot.workbench.agent.openjiuwen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentCoreDependencyProbeTest {
    @Test
    void resolvesExactlyThePinnedAgentCoreVersionAndRequiredApis() {
        assertEquals("0.1.13", AgentCoreDependencyProbe.readVersion());
        assertDoesNotThrow(AgentCoreDependencyProbe::verify);
    }
}
