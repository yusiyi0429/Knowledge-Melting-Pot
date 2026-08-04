package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates safe RESOURCE_ONLY metadata and the bounded SANDBOX_V1 declarative
 * program. Arbitrary source code is never executed and manifests cannot carry
 * scripts, binaries, commands, or secrets — including inside nested objects or
 * array elements.
 */
@Component
public final class SkillManifestValidator {
    public static final String RESOURCE_ONLY = "RESOURCE_ONLY";
    public static final String SANDBOX_V1 = "SANDBOX_V1";
    private static final int MAX_MANIFEST_BYTES = 8192;
    private static final int RESOURCE_MAX_DEPTH = 3;
    private static final int SANDBOX_MAX_DEPTH = 6;
    private static final Set<String> RESOURCE_KEYS = Set.of(
            "schemaVersion", "executionMode", "name", "description", "resources", "prompt",
            "schema", "files", "metadata", "modelHints");
    private static final Set<String> SANDBOX_KEYS = Set.of(
            "schemaVersion", "executionMode", "name", "description", "program", "metadata");
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "script", "scripts", "command", "commands", "executable", "binary", "attachment",
            "attachments", "code", "python", "shell", "entrypoint", "credentials", "apikey",
            "api_key", "secret", "token");

    private final ObjectMapper objectMapper;

    public SkillManifestValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Validates and returns the normalized manifest JSON (compact object form). */
    public String validate(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) {
            throw new IllegalArgumentException("skill manifest is required");
        }
        byte[] bytes = manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("skill manifest must not exceed " + MAX_MANIFEST_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(manifestJson);
        } catch (IOException exception) {
            throw new IllegalArgumentException("skill manifest must be valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("skill manifest must be a JSON object");
        }
        if (root.size() > 32) {
            throw new IllegalArgumentException("skill manifest has too many top-level fields");
        }
        JsonNode executionMode = root.get("executionMode");
        String mode = executionMode == null ? "" : executionMode.asText();
        if (!RESOURCE_ONLY.equals(mode) && !SANDBOX_V1.equals(mode)) {
            throw new IllegalArgumentException(
                    "skill manifest executionMode must be RESOURCE_ONLY or SANDBOX_V1");
        }
        Set<String> allowedKeys = RESOURCE_ONLY.equals(mode) ? RESOURCE_KEYS : SANDBOX_KEYS;
        assertDepth(root, 1, RESOURCE_ONLY.equals(mode) ? RESOURCE_MAX_DEPTH : SANDBOX_MAX_DEPTH);
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException("skill manifest field is not allowed: " + key);
            }
        }
        // Forbidden keys are rejected at every nesting level (objects and array elements).
        assertNoForbiddenKeys(root);
        if (SANDBOX_V1.equals(mode)) {
            validateSandboxProgram(root.get("program"));
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.set("executionMode", objectMapper.getNodeFactory().textNode(mode));
        root.fields().forEachRemaining(entry -> normalized.set(entry.getKey(), entry.getValue()));
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (IOException exception) {
            throw new IllegalArgumentException("skill manifest cannot be serialized");
        }
    }

    private static void assertDepth(JsonNode node, int depth, int maxDepth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("skill manifest exceeds the maximum nesting depth");
        }
        node.forEach(child -> {
            if (child.isObject() || child.isArray()) {
                assertDepth(child, depth + 1, maxDepth);
            }
        });
    }

    private static void validateSandboxProgram(JsonNode program) {
        if (program == null || !program.isObject()
                || !Set.of("kind", "rules", "defaultPrediction").equals(fieldNames(program))) {
            throw new IllegalArgumentException("SANDBOX_V1 program must contain exactly kind, rules and defaultPrediction");
        }
        if (!"CLASSIFY_CONTAINS".equals(text(program, "kind", 64))) {
            throw new IllegalArgumentException("SANDBOX_V1 program kind must be CLASSIFY_CONTAINS");
        }
        text(program, "defaultPrediction", 500);
        JsonNode rules = program.get("rules");
        if (rules == null || !rules.isArray() || rules.isEmpty() || rules.size() > 100) {
            throw new IllegalArgumentException("SANDBOX_V1 rules must contain between 1 and 100 entries");
        }
        rules.forEach(rule -> {
            if (!rule.isObject() || !Set.of("containsAny", "prediction").equals(fieldNames(rule))) {
                throw new IllegalArgumentException("SANDBOX_V1 rule contains unsupported fields");
            }
            text(rule, "prediction", 500);
            JsonNode tokens = rule.get("containsAny");
            if (tokens == null || !tokens.isArray() || tokens.isEmpty() || tokens.size() > 20) {
                throw new IllegalArgumentException("SANDBOX_V1 containsAny must contain between 1 and 20 tokens");
            }
            tokens.forEach(token -> {
                if (!token.isTextual() || token.asText().isBlank() || token.asText().length() > 200) {
                    throw new IllegalArgumentException("SANDBOX_V1 token is invalid");
                }
            });
        });
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maxLength) {
            throw new IllegalArgumentException("SANDBOX_V1 field is invalid: " + field);
        }
        return value.asText();
    }

    /** Rejects forbidden keys at every object level, including inside array elements. */
    private static void assertNoForbiddenKeys(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (FORBIDDEN_KEYS.contains(entry.getKey().toLowerCase())) {
                    throw new IllegalArgumentException("skill manifest field is forbidden: " + entry.getKey());
                }
                assertNoForbiddenKeys(entry.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(SkillManifestValidator::assertNoForbiddenKeys);
        }
    }
}
