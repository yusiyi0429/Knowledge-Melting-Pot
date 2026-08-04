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
