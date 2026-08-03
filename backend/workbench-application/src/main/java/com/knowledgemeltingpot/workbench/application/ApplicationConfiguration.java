package com.knowledgemeltingpot.workbench.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {
    @Bean
    Clock workbenchClock() {
        return Clock.systemUTC();
    }
}
