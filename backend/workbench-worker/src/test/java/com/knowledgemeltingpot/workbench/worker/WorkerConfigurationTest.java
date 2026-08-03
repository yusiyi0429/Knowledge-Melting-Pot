package com.knowledgemeltingpot.workbench.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkerConfigurationTest {
    @Test
    void objectMapperDiscoversJavaTimeAndUsesIsoDates() throws Exception {
        var objectMapper = new WorkerConfiguration().objectMapper();

        assertThat(objectMapper.writeValueAsString(Instant.parse("2026-08-03T08:00:00Z")))
                .isEqualTo("\"2026-08-03T08:00:00Z\"");
    }
}
