package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeMarkdownCodec {
    private static final String ERROR_CODE = "knowledge-markdown-invalid";
    private static final List<String> ALLOWED_BLOCKS = List.of(
            "kmp-metadata", "kmp-rule", "kmp-flow", "kmp-conflict", "kmp-gap", "kmp-source-ref");
    private final Parser parser = Parser.builder().build();
    private final ObjectMapper objectMapper;
    private final KnowledgeIrValidator validator;

    public KnowledgeMarkdownCodec(ObjectMapper objectMapper, KnowledgeIrValidator validator) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.validator = validator;
    }

    public String render(KnowledgeIr ir) {
        validator.validate(ir);
        StringBuilder markdown = new StringBuilder("# 知识文档\n\n")
                .append("<!-- KMP KnowledgeIR v1：请保留 kmp-* 结构块和来源引用。 -->\n\n")
                .append("## 元数据\n\n");
        block(markdown, "kmp-metadata", ir.metadata());
        section(markdown, "规则");
        for (KnowledgeIr.Rule rule : ir.rules()) {
            markdown.append("### ").append(rule.id()).append(' ').append(rule.title()).append("\n\n");
            block(markdown, "kmp-rule", rule);
        }
        section(markdown, "流程");
        for (KnowledgeIr.Flow flow : ir.flows()) {
            markdown.append("### ").append(flow.id()).append(' ').append(flow.name()).append("\n\n");
            block(markdown, "kmp-flow", flow);
        }
        section(markdown, "冲突");
        for (KnowledgeIr.Conflict conflict : ir.conflicts()) {
            block(markdown, "kmp-conflict", conflict);
        }
        section(markdown, "缺失信息");
        for (KnowledgeIr.Gap gap : ir.gaps()) {
            block(markdown, "kmp-gap", gap);
        }
        section(markdown, "来源");
        for (KnowledgeIr.SourceRef sourceRef : ir.sourceRefs()) {
            markdown.append("### [").append(sourceRef.code()).append("]\n\n");
            block(markdown, "kmp-source-ref", sourceRef);
        }
        return markdown.toString();
    }

    public KnowledgeIr parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw invalid("Document Markdown must not be blank");
        }
        Map<String, List<String>> blocks = collectBlocks(parser.parse(markdown));
        List<String> metadata = blocks.getOrDefault("kmp-metadata", List.of());
        if (metadata.size() != 1) {
            throw invalid("Document must contain exactly one kmp-metadata block");
        }
        try {
            KnowledgeIr ir = new KnowledgeIr(
                    KnowledgeIr.SCHEMA_VERSION,
                    read(metadata.getFirst(), KnowledgeIr.Metadata.class),
                    readAll(blocks.get("kmp-rule"), KnowledgeIr.Rule.class),
                    readAll(blocks.get("kmp-flow"), KnowledgeIr.Flow.class),
                    readAll(blocks.get("kmp-conflict"), KnowledgeIr.Conflict.class),
                    readAll(blocks.get("kmp-gap"), KnowledgeIr.Gap.class),
                    readAll(blocks.get("kmp-source-ref"), KnowledgeIr.SourceRef.class));
            return validator.validate(ir);
        } catch (UnprocessableEntityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("A kmp-* block is invalid: " + safeMessage(exception));
        }
    }

    private Map<String, List<String>> collectBlocks(Node root) {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        walk(root, blocks);
        for (String info : blocks.keySet()) {
            if (!ALLOWED_BLOCKS.contains(info)) {
                throw invalid("Unsupported KMP block: " + info);
            }
        }
        return blocks;
    }

    private void walk(Node node, Map<String, List<String>> blocks) {
        if (node instanceof FencedCodeBlock block) {
            String info = block.getInfo() == null ? "" : block.getInfo().strip();
            if (info.startsWith("kmp-")) {
                blocks.computeIfAbsent(info, ignored -> new ArrayList<>()).add(block.getLiteral());
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            walk(child, blocks);
        }
    }

    private <T> List<T> readAll(List<String> values, Class<T> type) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> read(value, type)).toList();
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw invalid("Invalid JSON in " + type.getSimpleName() + " block");
        }
    }

    private void block(StringBuilder markdown, String info, Object value) {
        markdown.append("``` ".replace(" ", "")).append(info).append('\n');
        try {
            markdown.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("KnowledgeIR could not be rendered", exception);
        }
        markdown.append("\n```\n\n");
    }

    private void section(StringBuilder markdown, String title) {
        markdown.append("## ").append(title).append("\n\n");
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "invalid value" : exception.getMessage();
    }

    private UnprocessableEntityException invalid(String detail) {
        return new UnprocessableEntityException(ERROR_CODE, detail);
    }
}
