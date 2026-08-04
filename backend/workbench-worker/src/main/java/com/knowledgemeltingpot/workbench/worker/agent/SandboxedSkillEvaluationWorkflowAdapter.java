package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowledgemeltingpot.workbench.application.port.SkillEvaluationWorkflowPort;
import com.knowledgemeltingpot.workbench.application.port.SkillRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Executes a released SANDBOX_V1 declarative Skill in the no-secret, no-egress
 * sandbox container. Model labels and expected values are not part of this protocol.
 */
@Component
@ConditionalOnProperty(name = "workbench.skill-sandbox.enabled", havingValue = "true")
public class SandboxedSkillEvaluationWorkflowAdapter implements SkillEvaluationWorkflowPort {
    private static final int MAX_RESPONSE_BYTES = 4096;
    private static final Set<String> ALLOWED_HOSTS = Set.of("skill-sandbox", "localhost", "127.0.0.1");
    private final SkillRepository skills;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public SandboxedSkillEvaluationWorkflowAdapter(SkillRepository skills, ObjectMapper objectMapper,
            @Value("${workbench.skill-sandbox.url:http://skill-sandbox:8081/v1/execute}") URI endpoint,
            @Value("${workbench.skill-sandbox.timeout:PT5S}") Duration timeout) {
        this.skills = skills;
        this.objectMapper = objectMapper;
        this.endpoint = validateEndpoint(endpoint);
        if (timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("skill sandbox timeout must be between PT0.1S and PT30S");
        }
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public EvaluationPrediction predict(EvaluationRequest request) {
        var version = skills.findVersion(request.skillVersionId())
                .orElseThrow(() -> new EvaluationWorkflowException("SKILL_VERSION_MISSING"));
        JsonNode manifest;
        try {
            manifest = objectMapper.readTree(version.manifestJson());
        } catch (IOException exception) {
            throw new EvaluationWorkflowException("SKILL_MANIFEST_INVALID");
        }
        if (manifest == null || !manifest.isObject()
                || !"SANDBOX_V1".equals(manifest.path("executionMode").asText())
                || !manifest.path("program").isObject()) {
            throw new EvaluationWorkflowException("SKILL_SANDBOX_MODE_REQUIRED");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("version", 1);
        payload.put("invocationId", request.evaluationRunId() + ":" + request.caseId());
        payload.set("program", manifest.path("program"));
        payload.put("input", request.input());
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() != 200) {
                    throw new EvaluationWorkflowException("SKILL_SANDBOX_REJECTED");
                }
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new EvaluationWorkflowException("SKILL_SANDBOX_RESPONSE_TOO_LARGE");
                }
                JsonNode result = objectMapper.readTree(bytes);
                if (result == null || !result.isObject() || result.size() != 1
                        || !result.path("prediction").isTextual()) {
                    throw new EvaluationWorkflowException("SKILL_SANDBOX_RESPONSE_INVALID");
                }
                return new EvaluationPrediction(result.path("prediction").asText());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EvaluationWorkflowException("SKILL_SANDBOX_INTERRUPTED");
        } catch (EvaluationWorkflowException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new EvaluationWorkflowException("SKILL_SANDBOX_UNAVAILABLE");
        }
    }

    private static URI validateEndpoint(URI value) {
        if (value == null || !"http".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                || !ALLOWED_HOSTS.contains(value.getHost().toLowerCase(Locale.ROOT))
                || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null
                || !"/v1/execute".equals(value.getPath())) {
            throw new IllegalArgumentException("skill sandbox URL must target the fixed internal execute endpoint");
        }
        return value;
    }
}
