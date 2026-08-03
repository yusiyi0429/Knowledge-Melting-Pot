package com.knowledgemeltingpot.workbench.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClamAvProperties.class)
@ConditionalOnProperty(prefix = "workbench.content", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContentAdapterConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "workbench.content.clamav", name = "enabled", havingValue = "true")
    ClamAvVirusScanPort clamAvVirusScanPort(ClamAvProperties properties) {
        return new ClamAvVirusScanPort(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    CompositeMaterialParser compositeMaterialParser() {
        return new CompositeMaterialParser();
    }
}
