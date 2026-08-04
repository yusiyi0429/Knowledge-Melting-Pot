package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.SkillEvaluationWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import com.knowledgemeltingpot.workbench.domain.SkillVersion;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SandboxedSkillEvaluationWorkflowAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOnlyDeclarativeProgramAndUnlabeledInputToFixedSandboxEndpoint() throws Exception {
        SkillRepository skills = mock(SkillRepository.class);
        UUID skillVersionId = UUID.randomUUID();
        String manifest = """
                {"executionMode":"SANDBOX_V1","program":{"kind":"CLASSIFY_CONTAINS","rules":[
                  {"containsAny":["逾期120"],"prediction":"次级"}],"defaultPrediction":"正常"}}
                """;
        when(skills.findVersion(skillVersionId)).thenReturn(Optional.of(new SkillVersion(skillVersionId,
                UUID.randomUUID(), 1, manifest, "a".repeat(64), UUID.randomUUID(), Instant.now())));
        AtomicReference<JsonNode> received = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/execute", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"prediction\":\"次级\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var adapter = new SandboxedSkillEvaluationWorkflowAdapter(skills, mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/execute"),
                Duration.ofSeconds(2));
        var request = new SkillEvaluationWorkflowPort.EvaluationRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), skillVersionId, "case-1", "客户逾期120天");

        var prediction = adapter.predict(request);

        assertThat(prediction.prediction()).isEqualTo("次级");
        assertThat(fieldNames(received.get())).containsExactlyInAnyOrder(
                "version", "invocationId", "program", "input");
        assertThat(received.get().toString()).doesNotContain("expected", "apiKey", "credential");
    }

    @Test
    void refusesResourceOnlySkillAndExternalEndpoint() {
        SkillRepository skills = mock(SkillRepository.class);
        UUID skillVersionId = UUID.randomUUID();
        when(skills.findVersion(skillVersionId)).thenReturn(Optional.of(new SkillVersion(skillVersionId,
                UUID.randomUUID(), 1, "{\"executionMode\":\"RESOURCE_ONLY\"}", "a".repeat(64),
                UUID.randomUUID(), Instant.now())));
        var adapter = new SandboxedSkillEvaluationWorkflowAdapter(skills, mapper,
                URI.create("http://localhost:8081/v1/execute"), Duration.ofSeconds(1));
        var request = new SkillEvaluationWorkflowPort.EvaluationRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), skillVersionId, "case-1", "input");

        assertThatThrownBy(() -> adapter.predict(request))
                .isInstanceOf(EvaluationWorkflowException.class)
                .hasMessage("SKILL_SANDBOX_MODE_REQUIRED");
        assertThatThrownBy(() -> new SandboxedSkillEvaluationWorkflowAdapter(skills, mapper,
                URI.create("https://example.com/v1/execute"), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed internal");
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
