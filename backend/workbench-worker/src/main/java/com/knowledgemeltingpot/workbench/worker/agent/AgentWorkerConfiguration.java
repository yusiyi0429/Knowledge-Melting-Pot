package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.CredentialCipher;
import com.knowledgemeltingpot.workbench.application.port.ModelConnectionRepository;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.application.security.ModelEndpointPolicy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentWorkerConfiguration {
    @Bean
    @ConditionalOnProperty(name = "workbench.agent.enabled", havingValue = "true")
    VersionedAgentExecutor versionedAgentExecutor(ModelConnectionRepository modelRepository,
            SkillRepository skillRepository, CredentialCipher credentialCipher,
            ModelEndpointPolicy endpointPolicy, ObjectMapper objectMapper,
            @Value("${workbench.agent.timeout:PT1M}") Duration timeout) {
        return new VersionedAgentExecutor(modelRepository, skillRepository, credentialCipher, endpointPolicy,
                objectMapper, timeout);
    }
}
