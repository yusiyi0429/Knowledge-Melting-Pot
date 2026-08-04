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
 * Validates skill manifests as safe, resource-only JSON metadata. The manifest
 * is never executed and cannot carry scripts, binaries, commands, or secrets —
 * including inside nested resources/prompt/schema objects or array elements.
 */
@Component
public final class SkillManifestValidator {
    public static final String EXECUTION_MODE = "RESOURCE_ONLY";
    private static final int MAX_MANIFEST_BYTES = 8192;
    private static final int MAX_DEPTH = 3;
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "schemaVersion", "executionMode", "name", "description", "resources", "prompt",
            "schema", "files", "metadata", "modelHints");
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "script", "scripts", "command", "commands", "executable", "binary", "attachment",
            "attachments", "code", "python", "shell", "entrypoint", "credentials", "apiKey",
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
        assertDepth(root, 1);
        JsonNode executionMode = root.get("executionMode");
        if (executionMode == null || !EXECUTION_MODE.equals(executionMode.asText())) {
            throw new IllegalArgumentException("skill manifest executionMode must be " + EXECUTION_MODE);
        }
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("skill manifest field is not allowed: " + key);
            }
        }
        // Forbidden keys are rejected at every nesting level (objects and array elements).
        assertNoForbiddenKeys(root);
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.set("executionMode", objectMapper.getNodeFactory().textNode(EXECUTION_MODE));
        root.fields().forEachRemaining(entry -> normalized.set(entry.getKey(), entry.getValue()));
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (IOException exception) {
            throw new IllegalArgumentException("skill manifest cannot be serialized");
        }
    }

    private static void assertDepth(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("skill manifest exceeds the maximum nesting depth");
        }
        node.forEach(child -> {
            if (child.isObject() || child.isArray()) {
                assertDepth(child, depth + 1);
            }
        });
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
