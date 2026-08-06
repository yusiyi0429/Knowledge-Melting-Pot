package com.knowledgemeltingpot.workbench.worker.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.port.MaterialSelection;
import com.knowledgemeltingpot.workbench.application.port.AssetGenerationWorkflowPort.AssetDraft;
import com.knowledgemeltingpot.workbench.domain.AssetType;
import com.knowledgemeltingpot.workbench.domain.DocumentRevision;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Deterministic, content-derived asset bundles. Everything is derived from the
 * finalized document Markdown plus the sub-scene/version identity; nothing is
 * fabricated and no private chain-of-thought is ever produced. EVALUATION_SET
 * only consumes safe holdout metadata.
 */
@Component
public final class AssetContentFactory {
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern ANCHOR = Pattern.compile("\\[SRC-[A-Za-z0-9_-]{1,100}]");
    /** Earliest DOS-representable zip timestamp (1980-01-01T00:00:00Z) for byte-stable bundles. */
    private static final long ZIP_ENTRY_TIME = 315532800000L;
    /** Stable OOXML core-property timestamp; POI otherwise injects the current wall clock. */
    private static final long XLSX_PROPERTY_TIME = 946684800000L;

    private final ObjectMapper objectMapper;

    public AssetContentFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] build(UUID subSceneId, AssetType type, int version, DocumentRevision revision,
            List<MaterialSelection> holdout) throws IOException {
        return switch (type) {
            case RULE_CATALOG -> ruleCatalog(subSceneId, version, revision, null);
            case DECISION_FLOW -> decisionFlow(subSceneId, version, revision, null);
            case SKILL_PACKAGE -> skillPackage(subSceneId, version, revision, null);
            case QA_PAIRS -> qaPairs(subSceneId, version, revision, null);
            case EVALUATION_SET -> throw new IllegalArgumentException(
                    "EVALUATION_SET must use buildEvaluationSet without document content");
        };
    }

    /** Production path: deterministic rendering and packaging of an already validated Agent draft. */
    public byte[] build(UUID subSceneId, AssetType type, int version, DocumentRevision revision,
            AssetDraft draft) throws IOException {
        if (draft == null || draft.items().isEmpty()) throw new IllegalArgumentException("validated Agent draft is required");
        return switch (type) {
            case RULE_CATALOG -> ruleCatalog(subSceneId, version, revision, draft);
            case DECISION_FLOW -> decisionFlow(subSceneId, version, revision, draft);
            case SKILL_PACKAGE -> skillPackage(subSceneId, version, revision, draft);
            case QA_PAIRS -> qaPairs(subSceneId, version, revision, draft);
            case EVALUATION_SET -> throw new IllegalArgumentException(
                    "EVALUATION_SET must use buildEvaluationSet without document content");
        };
    }

    /**
     * EVALUATION_SET rendering receives only safe identity metadata plus holdout
     * metadata; the document body is never passed into this path.
     */
    public byte[] buildEvaluationSet(UUID subSceneId, int version, long sourceRevision, String contentHash,
            List<MaterialSelection> holdout) throws IOException {
        return buildEvaluationSet(subSceneId, version, sourceRevision, contentHash, holdout, null);
    }

    public byte[] buildEvaluationSet(UUID subSceneId, int version, long sourceRevision, String contentHash,
            List<MaterialSelection> holdout, AssetDraft draft) throws IOException {
        List<Map<String, Object>> entries = holdout.stream()
                .sorted(Comparator.comparing(selection -> selection.material().id().toString()))
                .map(selection -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("materialId", selection.material().id());
                    entry.put("sha256", selection.material().sha256());
                    entry.put("sizeBytes", selection.material().sizeBytes());
                    entry.put("format", selection.material().format().name());
                    entry.put("partition", selection.binding().partition().name());
                    return entry;
                })
                .toList();
        Map<String, Object> payload = Map.of(
                "schemaVersion", "1.0",
                "subSceneId", subSceneId,
                "assetVersion", version,
                "sourceRevision", sourceRevision,
                "contentHash", contentHash,
                "holdoutCount", entries.size(),
                "entries", entries);
        Map<String, Object> schema = Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "required", List.of("materialId", "sha256", "sizeBytes", "format", "partition"),
                        "properties", Map.of(
                                "materialId", Map.of("type", "string", "format", "uuid"),
                                "sha256", Map.of("type", "string"),
                                "sizeBytes", Map.of("type", "integer"),
                                "format", Map.of("type", "string"),
                                "partition", Map.of("type", "string"))));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("evaluation.json", toJson(payload));
        files.put("schema.json", toJson(schema));
        if (draft != null) {
            files.put("agent-plan.json", toJson(Map.of("summary", draft.summary(), "items", draft.items())));
        }
        return zip(files);
    }

    // ---- RULE_CATALOG: rules.json + rules.xlsx --------------------------------

    private byte[] ruleCatalog(UUID subSceneId, int version, DocumentRevision revision, AssetDraft draft) throws IOException {
        List<Map<String, Object>> rules = draft == null ? extractRules(revision) : draft.items().stream().map(item -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("ruleId", item.id());
            rule.put("title", item.title());
            rule.put("basis", item.content());
            rule.put("sourceRefs", item.sourceRefs());
            rule.put("priority", item.tags().stream().findFirst().orElse("NORMAL"));
            return rule;
        }).toList();
        Map<String, Object> rulesJson = Map.of(
                "schemaVersion", "1.0",
                "subSceneId", subSceneId,
                "assetVersion", version,
                "sourceRevision", revision.revision(),
                "contentHash", revision.contentHash(),
                "rules", rules);
        byte[] json = toJson(rulesJson);
        // POI writes zip entries with the current timestamp; repack the XLSX so
        // the bundle is byte-stable across identical inputs.
        byte[] xlsx = deterministicRepack(rulesXlsx(rules));
        return zip(Map.of("rules.json", json, "rules.xlsx", xlsx));
    }

    private byte[] rulesXlsx(List<Map<String, Object>> rules) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var core = workbook.getProperties().getCoreProperties();
            core.setCreator("Knowledge-Melting-Pot");
            core.setLastModifiedByUser("Knowledge-Melting-Pot");
            core.setCreated(java.util.Optional.of(new Date(XLSX_PROPERTY_TIME)));
            core.setModified(java.util.Optional.of(new Date(XLSX_PROPERTY_TIME)));
            Sheet sheet = workbook.createSheet("规则清单");
            Row header = sheet.createRow(0);
            String[] columns = {"规则ID", "标题", "来源锚点", "优先级", "依据段落"};
            for (int index = 0; index < columns.length; index++) {
                header.createCell(index).setCellValue(columns[index]);
            }
            int rowIndex = 1;
            for (Map<String, Object> rule : rules) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(String.valueOf(rule.get("ruleId")));
                row.createCell(1).setCellValue(String.valueOf(rule.get("title")));
                row.createCell(2).setCellValue(((List<?>) rule.get("sourceRefs")).stream()
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
                row.createCell(3).setCellValue(String.valueOf(rule.get("priority")));
                row.createCell(4).setCellValue(String.valueOf(rule.get("basis")));
            }
            for (int index = 0; index < columns.length; index++) {
                sheet.autoSizeColumn(index);
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                workbook.write(output);
                return output.toByteArray();
            }
        }
    }

    // ---- DECISION_FLOW: flow.md + flow.json + flow.mermaid --------------------

    private byte[] decisionFlow(UUID subSceneId, int version, DocumentRevision revision, AssetDraft draft) throws IOException {
        List<Map<String, Object>> steps = draft == null ? extractSteps(revision) : draft.items().stream().map(item -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepId", item.id());
            step.put("title", item.title());
            step.put("basis", item.content());
            step.put("sourceRefs", item.sourceRefs());
            return step;
        }).toList();
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 研判流程\n\n");
        markdown.append("来源 Revision v").append(revision.revision()).append("（hash ")
                .append(revision.contentHash().substring(0, 12)).append("）\n\n");
        for (int index = 0; index < steps.size(); index++) {
            markdown.append("## 步骤 ").append(index + 1).append("：").append(steps.get(index).get("title"))
                    .append("\n\n").append(steps.get(index).get("basis")).append("\n\n");
        }
        markdown.append("> 本流程只包含可审计的业务步骤，不包含模型私有推理过程。\n");

        StringBuilder mermaid = new StringBuilder("graph TD\n");
        for (int index = 0; index < steps.size(); index++) {
            String node = "S" + (index + 1);
            mermaid.append("    ").append(node).append("[\"").append(escapeMermaid(steps.get(index).get("title")))
                    .append("\"]\n");
            if (index > 0) {
                mermaid.append("    S").append(index).append(" --> ").append(node).append("\n");
            }
        }

        Map<String, Object> flowJson = new LinkedHashMap<>();
        flowJson.put("schemaVersion", "1.0");
        flowJson.put("subSceneId", subSceneId);
        flowJson.put("assetVersion", version);
        flowJson.put("sourceRevision", revision.revision());
        flowJson.put("steps", steps);

        return zip(Map.of(
                "flow.md", markdown.toString().getBytes(StandardCharsets.UTF_8),
                "flow.json", toJson(flowJson),
                "flow.mermaid", mermaid.toString().getBytes(StandardCharsets.UTF_8)));
    }

    // ---- SKILL_PACKAGE: SKILL.md + prompt + schema + manifest -----------------

    private byte[] skillPackage(UUID subSceneId, int version, DocumentRevision revision, AssetDraft draft) throws IOException {
        String skillId = "skill-" + subSceneId + "-v" + version;
        String prompt = draft == null
                ? "你是面向场景【" + revision.subSceneId() + "】的知识萃取执行器。\n"
                        + "只依据提供的定稿文档（Revision v" + revision.revision() + "）与来源锚点作答；\n"
                        + "必须保留 [SRC-*] 来源引用；禁止输出推理链。\n"
                : draft.items().stream().map(item -> "## " + item.title() + "\n" + item.content())
                        .collect(java.util.stream.Collectors.joining("\n\n"));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("ruleId", "title", "basis", "sourceRefs"),
                "properties", Map.of(
                        "ruleId", Map.of("type", "string"),
                        "title", Map.of("type", "string"),
                        "basis", Map.of("type", "string"),
                        "sourceRefs", Map.of("type", "array", "items", Map.of("type", "string"))));
        List<Map<String, Object>> rules = extractRules(revision);
        byte[] skillBytes = ("# " + skillId + "\n\n"
                + "只读资源包：不含可执行脚本。\n\n"
                + "来源 Revision v" + revision.revision() + "，规则 " + rules.size() + " 条。\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] promptBytes = prompt.getBytes(StandardCharsets.UTF_8);
        byte[] schemaBytes = toJson(schema);
        Map<String, Object> manifest = Map.of(
                "schemaVersion", "1.0",
                "skillId", skillId,
                "subSceneId", subSceneId,
                "assetVersion", version,
                "sourceRevision", revision.revision(),
                "contentHash", revision.contentHash(),
                "executable", false,
                "files", List.of(
                        fileEntry("SKILL.md", sha256Hex(skillBytes)),
                        fileEntry("prompt.md", sha256Hex(promptBytes)),
                        fileEntry("schema.json", sha256Hex(schemaBytes))));
        return zip(Map.of(
                "SKILL.md", skillBytes,
                "prompt.md", promptBytes,
                "schema.json", schemaBytes,
                "manifest.json", toJson(manifest)));
    }

    private static Map<String, Object> fileEntry(String name, String sha256) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("sha256", sha256);
        return entry;
    }

    // ---- QA_PAIRS: qa.jsonl + schema + report ---------------------------------

    private byte[] qaPairs(UUID subSceneId, int version, DocumentRevision revision, AssetDraft draft) throws IOException {
        List<Map<String, Object>> pairs = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        int duplicates = 0;
        int withAnchors = 0;
        int withoutAnchors = 0;
        if (draft != null) {
            for (var item : draft.items()) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("id", item.id());
                pair.put("question", item.title());
                pair.put("answer", item.content());
                pair.put("sourceRefs", item.sourceRefs());
                pair.put("tags", item.tags());
                pairs.add(pair);
            }
            report.put("schema", "qa-pairs/v1");
            report.put("qaCount", pairs.size());
            report.put("agentSummary", draft.summary());
            report.put("duplicatesRemoved", 0);
            report.put("anchorCheck", Map.of("validated", true));
        }
        List<Map<String, Object>> paragraphs = draft == null ? paragraphRefs(revision) : List.of();
        for (Map<String, Object> paragraph : paragraphs) {
            List<?> refs = (List<?>) paragraph.get("refs");
            if (refs == null || refs.isEmpty()) {
                withoutAnchors++;
            } else {
                withAnchors++;
            }
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("question", "请依据文档概括段落要点：" + paragraph.get("refs"));
            pair.put("answer", paragraph.get("text"));
            pair.put("sourceRefs", paragraph.get("refs"));
            String key = String.valueOf(paragraph.get("text"));
            if (seen.contains(key)) {
                duplicates++;
                continue;
            }
            seen.add(key);
            pairs.add(pair);
        }
        report.put("schema", "qa-pairs/v1");
        report.put("totalParagraphs", paragraphs.size());
        report.put("qaCount", pairs.size());
        report.put("duplicatesRemoved", duplicates);
        report.put("anchorCheck", Map.of(
                "paragraphsWithAnchors", withAnchors,
                "paragraphsWithoutAnchors", withoutAnchors));

        Map<String, Object> schema = Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "required", List.of("question", "answer", "sourceRefs"),
                        "properties", Map.of(
                                "question", Map.of("type", "string"),
                                "answer", Map.of("type", "string"),
                                "sourceRefs", Map.of("type", "array", "items", Map.of("type", "string")))));
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> pair : pairs) {
            jsonl.append(objectMapper.writeValueAsString(pair)).append('\n');
        }
        return zip(Map.of(
                "qa.jsonl", jsonl.toString().getBytes(StandardCharsets.UTF_8),
                "schema.json", toJson(schema),
                "report.json", toJson(report)));
    }

    // ---- shared extraction helpers ---------------------------------------------

    private List<Map<String, Object>> extractRules(DocumentRevision revision) {
        List<Map<String, Object>> rules = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (String line : revision.content().split("\n")) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                headings.add(heading.group(2).trim());
                bodies.add("");
            } else if (!headings.isEmpty()) {
                bodies.set(bodies.size() - 1, bodies.get(bodies.size() - 1) + line.trim() + " ");
            }
        }
        for (int index = 0; index < headings.size(); index++) {
            String body = bodies.get(index).trim();
            List<String> refs = anchorRefs(body.isEmpty() ? headings.get(index) : body);
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("ruleId", "R" + String.format("%03d", index + 1));
            rule.put("title", headings.get(index));
            rule.put("basis", body.isEmpty() ? headings.get(index) : body);
            rule.put("sourceRefs", refs);
            rule.put("priority", "NORMAL");
            rules.add(rule);
        }
        return rules;
    }

    private List<Map<String, Object>> extractSteps(DocumentRevision revision) {
        List<Map<String, Object>> steps = new ArrayList<>();
        List<String> headings = extractRules(revision).stream().map(rule -> String.valueOf(rule.get("title"))).toList();
        for (int index = 0; index < headings.size(); index++) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepId", "S" + (index + 1));
            step.put("title", headings.get(index));
            step.put("basis", "依据文档标题与来源锚点生成的可审计步骤；不包含推理过程。");
            steps.add(step);
        }
        return steps;
    }

    private List<Map<String, Object>> paragraphRefs(DocumentRevision revision) {
        List<Map<String, Object>> paragraphs = new ArrayList<>();
        for (String line : revision.content().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || HEADING.matcher(trimmed).matches()) {
                continue;
            }
            List<String> refs = anchorRefs(trimmed);
            Map<String, Object> paragraph = new LinkedHashMap<>();
            paragraph.put("text", trimmed);
            paragraph.put("refs", refs);
            paragraphs.add(paragraph);
        }
        return paragraphs;
    }

    private List<String> anchorRefs(String text) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = ANCHOR.matcher(text);
        while (matcher.find()) {
            refs.add(matcher.group());
        }
        return refs;
    }

    private static String escapeMermaid(Object value) {
        return String.valueOf(value).replace("\"", "'");
    }

    private byte[] toJson(Object value) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    private byte[] zip(Map<String, byte[]> files) throws IOException {
        List<String> names = new ArrayList<>(files.keySet());
        java.util.Collections.sort(names);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(ZIP_ENTRY_TIME);
                zip.putNextEntry(entry);
                zip.write(files.get(name));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    /**
     * Re-compresses a zip (the POI XLSX output) with a fixed entry timestamp and
     * entries ordered by name, so the resulting bundle bytes are stable for
     * identical inputs regardless of the source zip's entry order.
     */
    private byte[] deterministicRepack(byte[] source) throws IOException {
        List<String> names = new ArrayList<>();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                input.transferTo(output);
                names.add(entry.getName());
                entries.put(entry.getName(), output.toByteArray());
            }
        }
        java.util.Collections.sort(names);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (String name : names) {
                ZipEntry repacked = new ZipEntry(name);
                repacked.setTime(ZIP_ENTRY_TIME);
                zip.putNextEntry(repacked);
                zip.write(entries.get(name));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
