package com.knowledgemeltingpot.workbench.worker.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionMode;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionRequest;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionResult;
import com.knowledgemeltingpot.workbench.agent.AgentExecutionStatus;
import com.knowledgemeltingpot.workbench.application.port.AssetGenerationWorkflowPort;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workbench.agent.enabled", havingValue = "true")
public class AgentAssetGenerationWorkflowAdapter implements AssetGenerationWorkflowPort {
    private static final int MAX_DOCUMENT_CHARS = 120_000;
    private static final int MAX_REPAIR_OUTPUT_CHARS = 20_000;
    private final VersionedAgentExecutor agent;
    private final ObjectMapper objectMapper;

    public AgentAssetGenerationWorkflowAdapter(VersionedAgentExecutor agent, ObjectMapper objectMapper) {
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssetDraft generate(AssetRequest request) {
        String prompt = prompt(request, false, "", "");
        String output = execute(request, prompt, "asset");
        try {
            return validate(request, parse(output));
        } catch (DraftValidationException | JsonProcessingException firstFailure) {
            String code = firstFailure instanceof DraftValidationException validation
                    ? validation.code : "ASSET_MODEL_JSON_INVALID";
            String repaired = execute(request, prompt(request, true, code, bounded(output)), "asset-repair");
            try {
                return validate(request, parse(repaired));
            } catch (DraftValidationException failure) {
                throw new WorkflowException(failure.code);
            } catch (JsonProcessingException failure) {
                throw new WorkflowException("ASSET_MODEL_JSON_INVALID");
            }
        }
    }

    private String execute(AssetRequest request, String prompt, String suffix) {
        AgentExecutionResult result = agent.stream(request.modelConfigVersionId(), request.skillVersionId(),
                new AgentExecutionRequest(request.jobId() + ":" + request.assetType() + ":" + suffix,
                        "system", prompt, AgentExecutionMode.WORKFLOW), ignored -> { });
        if (result.status() != AgentExecutionStatus.COMPLETED) {
            throw new WorkflowException(result.failureCode().isBlank() ? result.status().name() : result.failureCode());
        }
        return result.output();
    }

    private String prompt(AssetRequest request, boolean repair, String issue, String invalidOutput) {
        String context = request.assetType() == AssetType.EVALUATION_SET
                ? "隔离留出素材元数据（不得推断或生成答案）：\n" + toJson(request.holdoutSources())
                : "定稿知识文档（可能按输入预算截断）：\n" + boundedDocument(request.documentMarkdown())
                        + "\n允许的来源锚点：\n" + toJson(request.sourceRefCodes());
        String interpretation = switch (request.assetType()) {
            case RULE_CATALOG -> "每个 item 表示一条规则：id=规则ID，title=规则标题，content=条件与结论，sourceRefs=来源锚点。";
            case DECISION_FLOW -> "每个 item 表示一个按顺序排列的研判步骤：id=步骤ID，title=动作，content=可审计依据。";
            case SKILL_PACKAGE -> "items 表示 Skill 的提示词模块：id=模块ID，title=模块名，content=可执行说明；禁止脚本。";
            case QA_PAIRS -> "每个 item 表示 QA：id=用例ID，title=问题，content=答案，sourceRefs=来源锚点。";
            case EVALUATION_SET -> "每个 item 表示一份留出素材的评测登记：id=materialId，title=评测目标，content=空字符串，sourceRefs=[materialId]；不得生成期望答案。";
        };
        String repairInstruction = repair
                ? "\n上一次输出未通过校验（" + issue + "）。只修复结构，不增加上下文之外的事实。上次输出：\n" + invalidOutput
                : "";
        return """
                你正在生成知识工作台资产，资产类型：%s。
                返回单个 JSON 对象，字段严格为 summary 和 items。
                item 字段严格为 id、title、content、sourceRefs、tags。items 必须为非空数组，禁止 Markdown 代码围栏。
                %s
                所有内容必须可审计；不得输出模型私有推理过程，不得引入未提供来源。

                %s
                %s
                """.formatted(request.assetType(), interpretation, context, repairInstruction);
    }

    private AssetDraft parse(String value) throws JsonProcessingException {
        String text = value == null ? "" : value.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) text = text.substring(firstLine + 1, closing).strip();
        }
        JsonNode root = objectMapper.readTree(text);
        for (String wrapper : List.of("answer", "output", "result")) {
            if (root != null && root.isObject() && root.has(wrapper) && root.get(wrapper).isObject()) {
                root = root.get(wrapper);
                break;
            }
        }
        return objectMapper.treeToValue(root, AssetDraft.class);
    }

    private AssetDraft validate(AssetRequest request, AssetDraft draft) {
        if (draft == null || draft.items().isEmpty()) throw new DraftValidationException("ASSET_ITEMS_EMPTY");
        if (draft.items().size() > 500) throw new DraftValidationException("ASSET_ITEM_LIMIT_EXCEEDED");
        if (draft.summary().length() > 4_000) throw new DraftValidationException("ASSET_FIELD_TOO_LONG");
        Set<String> allowed = request.assetType() == AssetType.EVALUATION_SET
                ? request.holdoutSources().stream().map(source -> source.materialId().toString()).collect(java.util.stream.Collectors.toSet())
                : Set.copyOf(request.sourceRefCodes());
        Set<String> ids = new HashSet<>();
        for (DraftItem item : draft.items()) {
            if (blank(item.id()) || blank(item.title()) || item.content() == null) {
                throw new DraftValidationException("ASSET_REQUIRED_FIELD_MISSING");
            }
            if (!ids.add(item.id())) throw new DraftValidationException("ASSET_DUPLICATE_ITEM_ID");
            if (item.id().length() > 160 || item.title().length() > 500 || item.content().length() > 20_000
                    || item.tags().size() > 20 || item.sourceRefs().size() > 50) {
                throw new DraftValidationException("ASSET_FIELD_TOO_LONG");
            }
            if (item.sourceRefs().isEmpty() || item.sourceRefs().stream().anyMatch(ref -> !allowed.contains(ref))) {
                throw new DraftValidationException("ASSET_SOURCE_REFERENCE_INVALID");
            }
            if (request.assetType() == AssetType.EVALUATION_SET
                    && (!allowed.contains(item.id()) || !item.content().isBlank())) {
                throw new DraftValidationException("ASSET_HOLDOUT_BOUNDARY_INVALID");
            }
        }
        return draft;
    }

    private String boundedDocument(String value) {
        return value.length() <= MAX_DOCUMENT_CHARS ? value : value.substring(0, MAX_DOCUMENT_CHARS);
    }

    private String bounded(String value) {
        String text = value == null ? "" : value;
        return text.length() <= MAX_REPAIR_OUTPUT_CHARS ? text : text.substring(0, MAX_REPAIR_OUTPUT_CHARS);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("asset prompt data could not be serialized", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static final class DraftValidationException extends RuntimeException {
        private final String code;
        private DraftValidationException(String code) { super(code); this.code = code; }
    }

    public static final class WorkflowException extends RuntimeException {
        private final String code;
        public WorkflowException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
