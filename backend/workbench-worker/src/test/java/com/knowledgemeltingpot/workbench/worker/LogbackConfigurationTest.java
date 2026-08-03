package com.knowledgemeltingpot.workbench.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LogbackConfigurationTest {
    @Test
    void workerLoggingKeepsThirdPartyOutputMetadataOnlyAndApplicationMessagesDiagnosable() throws IOException {
        try (var resource = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(resource).isNotNull();
            String configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuration)
                    .contains("ch.qos.logback.core.ConsoleAppender", "%nopex",
                            "<pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{48}%nopex%n</pattern>",
                            "<pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{48} - %msg%nopex%n</pattern>",
                            "<logger name=\"com.knowledgemeltingpot\" level=\"INFO\" additivity=\"false\">",
                            "<logger name=\"com.openjiuwen\" level=\"WARN\"/>")
                    .doesNotContain("FileAppender", "RollingFileAppender", "%message", "%mdc", "%ex");
        }
    }
}
