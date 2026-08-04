package com.knowledgemeltingpot.workbench.domain;

import java.util.List;
import java.util.UUID;

/**
 * Versioned, provider-neutral projection of one knowledge document revision.
 * The Markdown representation is rendered from this value and must parse back
 * to the same value before a revision can be persisted.
 */
public record KnowledgeIr(
        String schemaVersion,
        Metadata metadata,
        List<Rule> rules,
        List<Flow> flows,
        List<Conflict> conflicts,
        List<Gap> gaps,
        List<SourceRef> sourceRefs) {

    public static final String SCHEMA_VERSION = "knowledge-ir/v1";

    public KnowledgeIr {
        schemaVersion = DomainChecks.text(schemaVersion, "schemaVersion");
        metadata = DomainChecks.required(metadata, "metadata");
        rules = immutable(rules);
        flows = immutable(flows);
        conflicts = immutable(conflicts);
        gaps = immutable(gaps);
        sourceRefs = immutable(sourceRefs);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record Metadata(UUID documentId, UUID subSceneId, UUID roundId, String inputHash) {
        public Metadata {
            documentId = DomainChecks.required(documentId, "documentId");
            subSceneId = DomainChecks.required(subSceneId, "subSceneId");
            roundId = DomainChecks.required(roundId, "roundId");
            inputHash = DomainChecks.text(inputHash, "inputHash");
        }
    }

    public record Rule(
            String id,
            String title,
            String condition,
            String conclusion,
            int priority,
            List<String> exceptions,
            List<String> sourceRefs) {
        public Rule {
            id = DomainChecks.text(id, "rule.id");
            title = DomainChecks.text(title, "rule.title");
            condition = DomainChecks.text(condition, "rule.condition");
            conclusion = DomainChecks.text(conclusion, "rule.conclusion");
            if (priority < 0 || priority > 1000) {
                throw new IllegalArgumentException("rule.priority must be between 0 and 1000");
            }
            exceptions = immutable(exceptions);
            sourceRefs = immutable(sourceRefs);
        }
    }

    public record Flow(String id, String name, List<FlowNode> nodes, List<FlowEdge> edges) {
        public Flow {
            id = DomainChecks.text(id, "flow.id");
            name = DomainChecks.text(name, "flow.name");
            nodes = immutable(nodes);
            edges = immutable(edges);
        }
    }

    public record FlowNode(String id, String label, boolean critical, List<String> sourceRefs) {
        public FlowNode {
            id = DomainChecks.text(id, "flowNode.id");
            label = DomainChecks.text(label, "flowNode.label");
            sourceRefs = immutable(sourceRefs);
        }
    }

    public record FlowEdge(String id, String source, String target, String label) {
        public FlowEdge {
            id = DomainChecks.text(id, "flowEdge.id");
            source = DomainChecks.text(source, "flowEdge.source");
            target = DomainChecks.text(target, "flowEdge.target");
            label = label == null ? "" : label;
        }
    }

    public record Conflict(String id, String description, List<String> sourceRefs) {
        public Conflict {
            id = DomainChecks.text(id, "conflict.id");
            description = DomainChecks.text(description, "conflict.description");
            sourceRefs = immutable(sourceRefs);
        }
    }

    public record Gap(String id, String description) {
        public Gap {
            id = DomainChecks.text(id, "gap.id");
            description = DomainChecks.text(description, "gap.description");
        }
    }

    public record SourceRef(
            String code,
            UUID materialId,
            String materialSha256,
            UUID chunkId,
            String locatorType,
            Integer page,
            Integer paragraph,
            Integer table,
            String sheet,
            Integer rowStart,
            Integer rowEnd,
            Integer colStart,
            Integer colEnd,
            Integer lineStart,
            Integer lineEnd,
            String excerptHash) {
        public SourceRef {
            code = DomainChecks.text(code, "sourceRef.code");
            materialId = DomainChecks.required(materialId, "sourceRef.materialId");
            materialSha256 = DomainChecks.text(materialSha256, "sourceRef.materialSha256");
            chunkId = DomainChecks.required(chunkId, "sourceRef.chunkId");
            locatorType = DomainChecks.text(locatorType, "sourceRef.locatorType");
            excerptHash = DomainChecks.text(excerptHash, "sourceRef.excerptHash");
        }
    }
}
