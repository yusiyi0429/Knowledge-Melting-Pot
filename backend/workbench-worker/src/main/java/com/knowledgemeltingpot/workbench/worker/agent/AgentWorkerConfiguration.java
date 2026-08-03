package com.knowledgemeltingpot.workbench.worker.agent;

import com.knowledgemeltingpot.workbench.agent.AgentModelConfiguration;
import com.knowledgemeltingpot.workbench.agent.DefaultKnowledgeExtractionAdapter;
import com.knowledgemeltingpot.workbench.agent.KnowledgeExtractionPort;
import com.knowledgemeltingpot.workbench.agent.ModelProvider;
import com.knowledgemeltingpot.workbench.agent.openjiuwen.OpenJiuwenAgentRuntimeFactory;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentWorkerConfiguration {
    @Bean
    @ConditionalOnProperty(name = "workbench.agent.enabled", havingValue = "true")
    KnowledgeExtractionPort knowledgeExtractionPort(
            @Value("${workbench.agent.provider:OPENAI_COMPATIBLE}") ModelProvider provider,
            @Value("${workbench.agent.model-name}") String modelName,
            @Value("${workbench.agent.api-base}") URI apiBase,
            @Value("${workbench.agent.api-key}") String apiKey,
            @Value("${workbench.agent.timeout:PT1M}") Duration timeout,
            @Value("${workbench.agent.temperature:0.2}") double temperature) {
        AgentModelConfiguration configuration = AgentModelConfiguration.builder()
                .provider(provider)
                .modelName(modelName)
                .apiBase(apiBase)
                .apiKey(apiKey)
                .timeout(timeout)
                .temperature(temperature)
                .verifySsl(true)
                .build();
        return new DefaultKnowledgeExtractionAdapter(new OpenJiuwenAgentRuntimeFactory(configuration));
    }
}
