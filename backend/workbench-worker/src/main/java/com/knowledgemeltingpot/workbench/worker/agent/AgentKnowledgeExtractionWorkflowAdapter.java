package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.application.port.KnowledgeExtractionWorkflowPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "workbench.agent.enabled", havingValue = "true")
public class AgentKnowledgeExtractionWorkflowAdapter implements KnowledgeExtractionWorkflowPort {
    private static final System.Logger LOGGER =
            System.getLogger(AgentKnowledgeExtractionWorkflowAdapter.class.getName());
    private static final int MAX_FORMAT_ATTEMPTS = 4;
    private static final int MIN_SEMANTIC_CODE_POINTS = 6;
    static final String KNOWLEDGE_DRAFT_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["rules", "flows", "conflicts", "gaps"],
              "properties": {
                "rules": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["title", "condition", "conclusion", "priority", "exceptions", "sourceRefs"],
                    "properties": {
                      "title": {"type": "string"},
                      "condition": {"type": "string"},
                      "conclusion": {"type": "string"},
                      "priority": {"type": "integer", "minimum": 0, "maximum": 1000},
                      "exceptions": {"type": "array", "items": {"type": "string"}},
                      "sourceRefs": {"type": "array", "items": {"type": "string"}}
                    }
                  }
                },
                "flows": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["id", "name", "nodes", "edges"],
                    "properties": {
                      "id": {"type": "string", "pattern": "^F-[A-Za-z0-9_-]{1,80}$"},
                      "name": {"type": "string"},
                      "nodes": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "additionalProperties": false,
                          "required": ["id", "label", "critical", "sourceRefs"],
                          "properties": {
                            "id": {"type": "string", "pattern": "^N-[A-Za-z0-9_-]{1,80}$"},
                            "label": {"type": "string"},
                            "critical": {"type": "boolean"},
                            "sourceRefs": {"type": "array", "items": {"type": "string"}}
                          }
                        }
                      },
                      "edges": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "additionalProperties": false,
                          "required": ["id", "source", "target", "label"],
                          "properties": {
                            "id": {"type": "string", "pattern": "^E-[A-Za-z0-9_-]{1,80}$"},
                            "source": {"type": "string"},
                            "target": {"type": "string"},
                            "label": {"type": "string"}
                          }
                        }
                      }
                    }
                  }
                },
                "conflicts": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["id", "description", "sourceRefs"],
                    "properties": {
                      "id": {"type": "string", "pattern": "^C-[A-Za-z0-9_-]{1,80}$"},
                      "description": {"type": "string"},
                      "sourceRefs": {"type": "array", "items": {"type": "string"}}
                    }
                  }
                },
                "gaps": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["id", "description"],
                    "properties": {
                      "id": {"type": "string", "pattern": "^G-[A-Za-z0-9_-]{1,80}$"},
                      "description": {"type": "string"}
                    }
                  }
                }
              }
            }
            """;

    private final VersionedAgentExecutor agent;
    private final ObjectMapper objectMapper;

    private static final String FORMAT_RETRY_INSTRUCTION = """

            上一次调用未返回可解析的结构化结果。请重新执行本次任务，并严格遵守：
            1. 第一字符必须是 {，最后一字符必须是 }；
            2. 只输出一个 JSON 对象，不输出 Markdown、XML、说明或推理过程；
            3. 所有字段名和字符串必须使用双引号；
            4. 不得使用尾逗号；rules、flows、conflicts、gaps 四个数组必须全部存在。
            """;

    public AgentKnowledgeExtractionWorkflowAdapter(VersionedAgentExecutor agent, ObjectMapper objectMapper) {
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeDraft map(MapRequest request) {
        if (semanticCodePoints(request.content()) < MIN_SEMANTIC_CODE_POINTS) {
            return new KnowledgeDraft(List.of(), List.of(), List.of(), List.of());
        }
        String prompt = """
                你是知识萃取 Map 阶段。只能依据下方一个已验证来源，不得补充外部事实。
                生成结构化结果，字段严格为 rules、flows、conflicts、gaps。
                rules 每项字段为 title、condition、conclusion、priority、exceptions、sourceRefs；
                sourceRefs 只能使用给定来源编号。流程、冲突和缺失项必须使用 schema 中的完整字段；
                没有内容的数组必须返回 []。不要解释或输出推理过程。

                来源编号：%s
                来源定位：%s
                来源正文：
                %s

                输出必须符合以下 JSON Schema：
                %s
                """.formatted(request.sourceRefCode(), request.locator(), request.content(),
                KNOWLEDGE_DRAFT_SCHEMA);
        return execute(request.modelConfigVersionId(), request.skillVersionId(),
                request.runId().toString() + ":map:" + request.sourceRefCode(), prompt);
    }

    @Override
    public KnowledgeDraft reduce(ReduceRequest request) {
        // Do not ask a model to reproduce every Map result in one response: large source sets can exceed
        // provider output limits and truncate otherwise valid JSON. The facts were already extracted by
        // the version-pinned model; Reduce is deterministic so provenance and retry behavior stay stable.
        LinkedHashMap<String, RuleDraft> rules = new LinkedHashMap<>();
        LinkedHashSet<com.knowledgemeltingpot.workbench.domain.KnowledgeIr.Flow> flows = new LinkedHashSet<>();
        LinkedHashMap<String, com.knowledgemeltingpot.workbench.domain.KnowledgeIr.Conflict> conflicts =
                new LinkedHashMap<>();
        LinkedHashMap<String, com.knowledgemeltingpot.workbench.domain.KnowledgeIr.Gap> gaps =
                new LinkedHashMap<>();
        for (KnowledgeDraft draft : request.mapResults()) {
            for (RuleDraft rule : draft.rules()) {
                String key = normalized(rule.condition()) + "\n" + normalized(rule.conclusion());
                rules.merge(key, rule, this::mergeRuleDraft);
            }
            flows.addAll(draft.flows());
            for (var conflict : draft.conflicts()) {
                conflicts.merge(normalized(conflict.description()), conflict,
                        (left, right) -> new com.knowledgemeltingpot.workbench.domain.KnowledgeIr.Conflict(
                                left.id(), left.description(), merged(left.sourceRefs(), right.sourceRefs())));
            }
            for (var gap : draft.gaps()) {
                gaps.putIfAbsent(normalized(gap.description()), gap);
            }
        }
        return new KnowledgeDraft(List.copyOf(rules.values()), List.copyOf(flows),
                List.copyOf(conflicts.values()), List.copyOf(gaps.values()));
    }

    private RuleDraft mergeRuleDraft(RuleDraft left, RuleDraft right) {
        return new RuleDraft(left.title(), left.condition(), left.conclusion(),
                Math.max(left.priority(), right.priority()), merged(left.exceptions(), right.exceptions()),
                merged(left.sourceRefs(), right.sourceRefs()));
    }

    private List<String> merged(List<String> left, List<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>(left);
        values.addAll(right);
        return List.copyOf(values);
    }

    private String normalized(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private KnowledgeDraft execute(java.util.UUID modelConfigVersionId, java.util.UUID skillVersionId,
            String sessionId, String prompt) {
        for (int attempt = 1; attempt <= MAX_FORMAT_ATTEMPTS; attempt++) {
            String attemptSessionId = attempt == 1 ? sessionId : sessionId + ":format-retry-" + attempt;
            String attemptPrompt = attempt == 1 ? prompt : prompt + FORMAT_RETRY_INSTRUCTION;
            AgentExecutionResult result = agent.stream(modelConfigVersionId, skillVersionId,
                    new AgentExecutionRequest(attemptSessionId, "system", attemptPrompt, AgentExecutionMode.WORKFLOW),
                    ignored -> { });
            if (result.status() != AgentExecutionStatus.COMPLETED) {
                throw new WorkflowGenerationException(result.failureCode().isBlank()
                        ? result.status().name() : result.failureCode());
            }
            try {
                return parseOutput(result.output());
            } catch (JsonProcessingException exception) {
                logSafeJsonDiagnostic(attemptSessionId, result.output(), exception);
                if (attempt == MAX_FORMAT_ATTEMPTS) {
                    throw new WorkflowGenerationException("MODEL_JSON_INVALID");
                }
            }
        }
        throw new WorkflowGenerationException("MODEL_JSON_INVALID");
    }

    private int semanticCodePoints(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return (int) value.codePoints()
                .filter(codePoint -> Character.isLetterOrDigit(codePoint))
                .count();
    }

    KnowledgeDraft parseOutput(String value) throws JsonProcessingException {
        JsonProcessingException lastFailure = null;
        for (String candidate : jsonCandidates(value)) {
            try {
                JsonNode node = unwrap(readModelJson(candidate));
                if (isKnowledgeDraft(node)) {
                    return objectMapper.treeToValue(node, KnowledgeDraft.class);
                }
            } catch (JsonProcessingException exception) {
                lastFailure = exception;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw JsonMappingException.from((com.fasterxml.jackson.core.JsonParser) null,
                "Model output did not contain a KnowledgeDraft JSON object");
    }

    private JsonNode readModelJson(String candidate) throws JsonProcessingException {
        try (JsonParser parser = objectMapper.getFactory().createParser(candidate)) {
            parser.enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature());
            parser.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
            return objectMapper.readTree(parser);
        } catch (java.io.IOException exception) {
            if (exception instanceof JsonProcessingException jsonFailure) {
                throw jsonFailure;
            }
            throw JsonMappingException.fromUnexpectedIOE(exception);
        }
    }

    private List<String> jsonCandidates(String value) {
        String text = value == null ? "" : value.strip();
        List<String> candidates = new ArrayList<>();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                candidates.add(text.substring(firstLine + 1, closing).strip());
            }
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            candidates.add(text);
        }
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    candidates.add(text.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return candidates.stream().distinct().toList();
    }

    private JsonNode unwrap(JsonNode node) throws JsonProcessingException {
        JsonNode current = node;
        for (int depth = 0; depth < 3 && current != null; depth++) {
            if (isKnowledgeDraft(current)) {
                return current;
            }
            JsonNode nested = firstPresent(current, "answer", "output", "result");
            if (nested == null) {
                return current;
            }
            current = nested.isTextual() ? readModelJson(nested.asText()) : nested;
        }
        return current;
    }

    private JsonNode firstPresent(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String field : fields) {
            if (node.has(field)) {
                return node.get(field);
            }
        }
        return null;
    }

    private boolean isKnowledgeDraft(JsonNode node) {
        return node != null && node.isObject()
                && node.path("rules").isArray()
                && node.path("flows").isArray()
                && node.path("conflicts").isArray()
                && node.path("gaps").isArray();
    }

    private void logSafeJsonDiagnostic(String sessionId, String value, JsonProcessingException failure) {
        List<String> candidates = jsonCandidates(value);
        int draftCandidates = 0;
        for (String candidate : candidates) {
            try {
                if (isKnowledgeDraft(unwrap(readModelJson(candidate)))) {
                    draftCandidates++;
                }
            } catch (JsonProcessingException ignored) {
                // Only structural counts are logged; model content is intentionally discarded.
            }
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Agent JSON rejected for session {0}: length={1}, candidates={2}, draftCandidates={3}, "
                        + "failure={4}, path={5}",
                sessionId,
                value == null ? 0 : value.length(),
                candidates.size(),
                draftCandidates,
                failure.getClass().getSimpleName(),
                safePath(failure));
    }

    private String safePath(JsonProcessingException failure) {
        if (!(failure instanceof JsonMappingException mapping) || mapping.getPath().isEmpty()) {
            return "none";
        }
        return mapping.getPath().stream()
                .map(reference -> reference.getFieldName() == null
                        ? "[" + reference.getIndex() + "]"
                        : safeField(reference.getFieldName()))
                .reduce((left, right) -> left + "." + right)
                .orElse("none");
    }

    private String safeField(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9]{0,30}") ? value : "field";
    }

    public static final class WorkflowGenerationException extends RuntimeException {
        private final String code;

        WorkflowGenerationException(String code) {
            super("Agent workflow generation failed");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
