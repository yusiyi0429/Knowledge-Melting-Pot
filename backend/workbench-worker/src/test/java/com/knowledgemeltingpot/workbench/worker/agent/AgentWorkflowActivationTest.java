package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentWorkflowActivationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AgentKnowledgeExtractionWorkflowAdapter.class,
                    AgentKnowledgeAlignmentWorkflowAdapter.class,
                    AgentExtractionJobHandler.class,
                    AgentAlignmentJobHandler.class);

    @Test
    void agentAdaptersAndHandlersStayAbsentWhenBothExecutionModesAreDisabled() {
        contextRunner
                .withPropertyValues(
                        "workbench.agent.enabled=false",
                        "workbench.agent.test-stub-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AgentKnowledgeExtractionWorkflowAdapter.class);
                    assertThat(context).doesNotHaveBean(AgentKnowledgeAlignmentWorkflowAdapter.class);
                    assertThat(context).doesNotHaveBean(AgentExtractionJobHandler.class);
                    assertThat(context).doesNotHaveBean(AgentAlignmentJobHandler.class);
                });
    }
}
