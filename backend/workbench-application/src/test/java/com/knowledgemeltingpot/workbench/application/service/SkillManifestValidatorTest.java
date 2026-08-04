package com.knowledgemeltingpot.workbench.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SkillManifestValidatorTest {
    private final SkillManifestValidator validator = new SkillManifestValidator(new ObjectMapper());

    @Test
    void acceptsResourceOnlyManifestWithAllowedFields() {
        String manifest = """
                {"schemaVersion":"1.0","executionMode":"RESOURCE_ONLY","name":"rules",
                 "resources":["rules.json"],"prompt":{"system":"只读"},"schema":{"type":"object"}}
                """;

        String normalized = validator.validate(manifest);

        assertThat(normalized).contains("\"executionMode\":\"RESOURCE_ONLY\"");
    }

    @Test
    void acceptsOnlyTheBoundedDeclarativeSandboxProgram() {
        String manifest = """
                {"schemaVersion":"1.0","executionMode":"SANDBOX_V1","name":"loan-classifier",
                 "program":{"kind":"CLASSIFY_CONTAINS","rules":[
                   {"containsAny":["逾期120","严重减值"],"prediction":"次级"},
                   {"containsAny":["逾期30"],"prediction":"关注"}
                 ],"defaultPrediction":"正常"}}
                """;

        String normalized = validator.validate(manifest);

        assertThat(normalized).contains("\"executionMode\":\"SANDBOX_V1\"",
                "\"kind\":\"CLASSIFY_CONTAINS\"");
    }

    @Test
    void sandboxManifestRejectsCodeNetworkAndUnboundedProgramShapes() {
        for (String manifest : new String[]{
                "{\"executionMode\":\"SANDBOX_V1\",\"script\":\"print(1)\"}",
                "{\"executionMode\":\"SANDBOX_V1\",\"program\":{\"kind\":\"SHELL\",\"rules\":[],\"defaultPrediction\":\"x\"}}",
                "{\"executionMode\":\"SANDBOX_V1\",\"program\":{\"kind\":\"PYTHON\",\"rules\":[{\"containsAny\":[\"x\"],\"prediction\":\"y\"}],\"defaultPrediction\":\"z\"}}",
                "{\"executionMode\":\"SANDBOX_V1\",\"program\":{\"kind\":\"CLASSIFY_CONTAINS\",\"rules\":[{\"containsAny\":[\"x\"],\"prediction\":\"y\",\"url\":\"http://example.com\"}],\"defaultPrediction\":\"z\"}}",
                "{\"executionMode\":\"SANDBOX_V1\",\"program\":{\"kind\":\"CLASSIFY_CONTAINS\",\"rules\":[],\"defaultPrediction\":\"z\"}}",
        }) {
            assertThatThrownBy(() -> validator.validate(manifest))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsNonResourceOnlyExecutionMode() {
        assertThatThrownBy(() -> validator.validate(
                "{\"executionMode\":\"EXECUTE\",\"script\":\"rm -rf\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESOURCE_ONLY");
    }

    @Test
    void rejectsScriptsBinariesAndSecrets() {
        for (String manifest : new String[]{
                "{\"executionMode\":\"RESOURCE_ONLY\",\"script\":\"evil.sh\"}",
                "{\"executionMode\":\"RESOURCE_ONLY\",\"command\":\"python -c x\"}",
                "{\"executionMode\":\"RESOURCE_ONLY\",\"apiKey\":\"sk-123\"}",
                "{\"executionMode\":\"RESOURCE_ONLY\",\"executable\":\"bin/tool\"}",
        }) {
            assertThatThrownBy(() -> validator.validate(manifest))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsForbiddenKeysInsideNestedObjectsAndArrays() {
        for (String manifest : new String[]{
                "{\"executionMode\":\"RESOURCE_ONLY\",\"prompt\":{\"system\":\"x\",\"script\":\"evil.sh\"}}",
                "{\"executionMode\":\"RESOURCE_ONLY\",\"resources\":[{\"name\":\"rules.json\",\"command\":\"rm -rf\"}]}",
                "{\"executionMode\":\"RESOURCE_ONLY\",\"schema\":{\"properties\":{\"secret\":\"sk-123\"}}}",
                "{\"executionMode\":\"RESOURCE_ONLY\",\"metadata\":{\"nested\":{\"entrypoint\":\"go\"}}}",
        }) {
            assertThatThrownBy(() -> validator.validate(manifest))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("forbidden");
        }
    }

    @Test
    void rejectsUnknownFieldsDeepNestingAndMalformedJson() {
        assertThatThrownBy(() -> validator.validate(
                "{\"executionMode\":\"RESOURCE_ONLY\",\"unexpected\":1}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> validator.validate(
                "{\"executionMode\":\"RESOURCE_ONLY\",\"a\":{\"b\":{\"c\":{\"d\":1}}}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting");
        assertThatThrownBy(() -> validator.validate("not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    void rejectsOversizedManifest() {
        StringBuilder large = new StringBuilder("{\"executionMode\":\"RESOURCE_ONLY\",\"description\":\"");
        large.append("x".repeat(9000));
        large.append("\"}");

        assertThatThrownBy(() -> validator.validate(large.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bytes");
    }
}
