package com.knowledgemeltingpot.workbench.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgemeltingpot.workbench.application.error.UnprocessableEntityException;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeIrValidator {
    private static final String ERROR_CODE = "knowledge-ir-invalid";
    private final ObjectMapper objectMapper;
    private final Schema schema;

    public KnowledgeIrValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream input = new ClassPathResource("schema/knowledge-ir-v1.schema.json").getInputStream()) {
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(input);
        } catch (IOException exception) {
            throw new IllegalStateException("KnowledgeIR schema could not be loaded", exception);
        }
    }

    public KnowledgeIr validate(KnowledgeIr ir) {
        if (ir == null) {
            throw invalid("KnowledgeIR is required");
        }
        JsonNode node = objectMapper.valueToTree(ir);
        List<Error> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .limit(10)
                    .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                    .collect(Collectors.joining("; "));
            throw invalid("KnowledgeIR JSON Schema validation failed: " + detail);
        }
        validateSemantics(ir);
        return ir;
    }

    /** Assigns server-owned stable rule IDs before the full validation pass. */
    public KnowledgeIr assignStableRuleIds(KnowledgeIr ir) {
        List<KnowledgeIr.Rule> rules = ir.rules().stream()
                .map(rule -> new KnowledgeIr.Rule(stableRuleId(ir.metadata(), rule), rule.title(), rule.condition(),
                        rule.conclusion(), rule.priority(), rule.exceptions(), rule.sourceRefs()))
                .toList();
        return new KnowledgeIr(ir.schemaVersion(), ir.metadata(), rules, ir.flows(), ir.conflicts(), ir.gaps(),
                ir.sourceRefs());
    }

    /**
     * Replaces model-authored identifiers with deterministic server-owned identifiers and removes
     * unsupported graph references before the strict validation pass. Model output remains subject
     * to the complete JSON Schema and source-reference checks after normalization.
     */
    public KnowledgeIr normalizeGenerated(KnowledgeIr ir) {
        LinkedHashMap<String, KnowledgeIr.Rule> rules = new LinkedHashMap<>();
        for (KnowledgeIr.Rule rule : ir.rules()) {
            KnowledgeIr.Rule normalized = new KnowledgeIr.Rule(stableRuleId(ir.metadata(), rule),
                    rule.title(), rule.condition(), rule.conclusion(), rule.priority(),
                    distinct(rule.exceptions()), distinct(rule.sourceRefs()));
            rules.merge(normalized.id(), normalized, this::mergeRuleEvidence);
        }

        List<KnowledgeIr.Flow> flows = new ArrayList<>();
        for (int flowIndex = 0; flowIndex < ir.flows().size(); flowIndex++) {
            KnowledgeIr.Flow flow = ir.flows().get(flowIndex);
            String flowId = stableGeneratedId("F-", flow.name(), Integer.toString(flowIndex));
            List<KnowledgeIr.FlowNode> nodes = new ArrayList<>();
            Map<String, String> nodeIds = new LinkedHashMap<>();
            for (int nodeIndex = 0; nodeIndex < flow.nodes().size(); nodeIndex++) {
                KnowledgeIr.FlowNode node = flow.nodes().get(nodeIndex);
                String nodeId = stableGeneratedId("N-", flowId, node.label(), Integer.toString(nodeIndex));
                List<String> refs = distinct(node.sourceRefs());
                nodes.add(new KnowledgeIr.FlowNode(nodeId, node.label(), node.critical() && !refs.isEmpty(), refs));
                nodeIds.putIfAbsent(node.id(), nodeId);
            }
            List<KnowledgeIr.FlowEdge> edges = new ArrayList<>();
            for (int edgeIndex = 0; edgeIndex < flow.edges().size(); edgeIndex++) {
                KnowledgeIr.FlowEdge edge = flow.edges().get(edgeIndex);
                String source = nodeIds.get(edge.source());
                String target = nodeIds.get(edge.target());
                if (source == null || target == null) {
                    continue;
                }
                String edgeId = stableGeneratedId("E-", flowId, source, target, edge.label(),
                        Integer.toString(edgeIndex));
                edges.add(new KnowledgeIr.FlowEdge(edgeId, source, target, edge.label()));
            }
            flows.add(new KnowledgeIr.Flow(flowId, flow.name(), nodes, edges));
        }

        List<KnowledgeIr.Conflict> conflicts = new ArrayList<>();
        for (int index = 0; index < ir.conflicts().size(); index++) {
            KnowledgeIr.Conflict conflict = ir.conflicts().get(index);
            List<String> refs = distinct(conflict.sourceRefs());
            if (!refs.isEmpty()) {
                conflicts.add(new KnowledgeIr.Conflict(
                        stableGeneratedId("C-", conflict.description(), Integer.toString(index)),
                        conflict.description(), refs));
            }
        }

        List<KnowledgeIr.Gap> gaps = new ArrayList<>();
        for (int index = 0; index < ir.gaps().size(); index++) {
            KnowledgeIr.Gap gap = ir.gaps().get(index);
            gaps.add(new KnowledgeIr.Gap(stableGeneratedId("G-", gap.description(), Integer.toString(index)),
                    gap.description()));
        }
        return new KnowledgeIr(ir.schemaVersion(), ir.metadata(), List.copyOf(rules.values()), flows,
                conflicts, gaps, ir.sourceRefs());
    }

    public String stableRuleId(KnowledgeIr.Metadata metadata, KnowledgeIr.Rule rule) {
        String normalized = normalize(metadata.subSceneId().toString()) + "\n"
                + normalize(rule.condition()) + "\n" + normalize(rule.conclusion());
        return "R-" + Hashes.sha256(normalized).substring(0, 16);
    }

    private KnowledgeIr.Rule mergeRuleEvidence(KnowledgeIr.Rule left, KnowledgeIr.Rule right) {
        List<String> exceptions = new ArrayList<>(left.exceptions());
        exceptions.addAll(right.exceptions());
        List<String> refs = new ArrayList<>(left.sourceRefs());
        refs.addAll(right.sourceRefs());
        return new KnowledgeIr.Rule(left.id(), left.title(), left.condition(), left.conclusion(),
                Math.max(left.priority(), right.priority()), distinct(exceptions), distinct(refs));
    }

    private String stableGeneratedId(String prefix, String... values) {
        return prefix + Hashes.sha256(String.join("\n", values)).substring(0, 16);
    }

    private <T> List<T> distinct(List<T> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private void validateSemantics(KnowledgeIr ir) {
        if (!KnowledgeIr.SCHEMA_VERSION.equals(ir.schemaVersion())) {
            throw invalid("Unsupported KnowledgeIR schemaVersion: " + ir.schemaVersion());
        }
        Set<String> sourceCodes = unique(ir.sourceRefs().stream().map(KnowledgeIr.SourceRef::code).toList(),
                "source reference code");
        unique(ir.sourceRefs().stream().map(ref -> ref.chunkId().toString()).toList(), "source reference chunk");
        unique(ir.rules().stream().map(KnowledgeIr.Rule::id).toList(), "rule id");
        unique(ir.flows().stream().map(KnowledgeIr.Flow::id).toList(), "flow id");
        unique(ir.conflicts().stream().map(KnowledgeIr.Conflict::id).toList(), "conflict id");
        unique(ir.gaps().stream().map(KnowledgeIr.Gap::id).toList(), "gap id");

        for (KnowledgeIr.SourceRef sourceRef : ir.sourceRefs()) {
            validateLocator(sourceRef);
        }

        for (KnowledgeIr.Rule rule : ir.rules()) {
            String expected = stableRuleId(ir.metadata(), rule);
            if (!expected.equals(rule.id())) {
                throw invalid("Rule " + rule.id() + " does not use its server-derived stable ID " + expected);
            }
            requireSources("Rule " + rule.id(), rule.sourceRefs(), sourceCodes, true);
        }
        for (KnowledgeIr.Conflict conflict : ir.conflicts()) {
            requireSources("Conflict " + conflict.id(), conflict.sourceRefs(), sourceCodes, true);
        }
        for (KnowledgeIr.Flow flow : ir.flows()) {
            Map<String, KnowledgeIr.FlowNode> nodes = uniqueBy(flow.nodes(), KnowledgeIr.FlowNode::id,
                    "node id in flow " + flow.id());
            unique(flow.edges().stream().map(KnowledgeIr.FlowEdge::id).toList(),
                    "edge id in flow " + flow.id());
            for (KnowledgeIr.FlowNode node : flow.nodes()) {
                requireSources("Flow node " + node.id(), node.sourceRefs(), sourceCodes, node.critical());
            }
            for (KnowledgeIr.FlowEdge edge : flow.edges()) {
                if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                    throw invalid("Flow edge " + edge.id() + " references an unknown node");
                }
            }
        }
    }

    private void validateLocator(KnowledgeIr.SourceRef ref) {
        switch (ref.locatorType()) {
            case "PDF_PAGE_PARAGRAPH" -> requireCoordinates(ref, ref.page(), ref.paragraph());
            case "DOCX_PARAGRAPH" -> requireCoordinates(ref, ref.paragraph());
            case "DOCX_TABLE_CELL" -> requireCoordinates(
                    ref, ref.table(), ref.rowStart(), ref.rowEnd(), ref.colStart(), ref.colEnd());
            case "XLSX_RANGE" -> {
                if (ref.sheet() == null || ref.sheet().isBlank()) {
                    throw invalid("Source " + ref.code() + " requires a sheet locator");
                }
                requireCoordinates(ref, ref.rowStart(), ref.rowEnd(), ref.colStart(), ref.colEnd());
            }
            case "TXT_LINES" -> requireCoordinates(ref, ref.lineStart(), ref.lineEnd());
            default -> throw invalid("Source " + ref.code() + " has an unsupported locator type");
        }
        requireOrdered(ref, "row", ref.rowStart(), ref.rowEnd());
        requireOrdered(ref, "column", ref.colStart(), ref.colEnd());
        requireOrdered(ref, "line", ref.lineStart(), ref.lineEnd());
    }

    private void requireCoordinates(KnowledgeIr.SourceRef ref, Integer... coordinates) {
        for (Integer coordinate : coordinates) {
            if (coordinate == null) {
                throw invalid("Source " + ref.code() + " is missing coordinates for " + ref.locatorType());
            }
        }
    }

    private void requireOrdered(KnowledgeIr.SourceRef ref, String label, Integer start, Integer end) {
        if (start != null && end != null && end < start) {
            throw invalid("Source " + ref.code() + " has an invalid " + label + " range");
        }
    }

    private void requireSources(String owner, List<String> refs, Set<String> available, boolean required) {
        if (required && refs.isEmpty()) {
            throw invalid(owner + " requires at least one source reference");
        }
        if (new HashSet<>(refs).size() != refs.size()) {
            throw invalid(owner + " contains duplicate source references");
        }
        for (String ref : refs) {
            if (!available.contains(ref)) {
                throw invalid(owner + " references unknown source " + ref);
            }
        }
    }

    private Set<String> unique(List<String> values, String label) {
        Set<String> result = new LinkedHashSet<>(values);
        if (result.size() != values.size()) {
            throw invalid("Duplicate " + label);
        }
        return result;
    }

    private <T> Map<String, T> uniqueBy(List<T> values, Function<T, String> id, String label) {
        Map<String, T> result = values.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
        if (result.size() != values.size()) {
            throw invalid("Duplicate " + label);
        }
        return result;
    }

    private String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private UnprocessableEntityException invalid(String detail) {
        return new UnprocessableEntityException(ERROR_CODE, detail);
    }
}
