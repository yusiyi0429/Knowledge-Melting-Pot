package com.knowledgemeltingpot.workbench.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityProblemWriterTest {
    @Test
    void securityProblemAlwaysContainsStableCodeAndTraceId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", "trace-123")) {
            new SecurityProblemWriter(objectMapper).write(response, 403, "Access denied", "Insufficient permission",
                    "access-denied");
        }

        JsonNode json = objectMapper.readTree(response.getContentAsByteArray());
        JsonNode properties = json.has("properties") ? json.path("properties") : json;
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(properties.path("code").asText()).isEqualTo("access-denied");
        assertThat(properties.path("traceId").asText()).isEqualTo("trace-123");
    }
}
