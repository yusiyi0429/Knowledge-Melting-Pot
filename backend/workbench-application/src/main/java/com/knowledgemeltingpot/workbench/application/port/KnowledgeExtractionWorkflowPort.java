package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.List;
import java.util.UUID;

/** Typed boundary between the business workflow and any Agent runtime. */
public interface KnowledgeExtractionWorkflowPort {
    KnowledgeDraft map(MapRequest request);

    KnowledgeDraft reduce(ReduceRequest request);

    record MapRequest(UUID runId, UUID modelConfigVersionId, UUID skillVersionId,
            String sourceRefCode, String locator, String content) {
    }

    record ReduceRequest(UUID runId, UUID modelConfigVersionId, UUID skillVersionId,
            List<KnowledgeDraft> mapResults) {
    }

    record KnowledgeDraft(
            List<RuleDraft> rules,
            List<KnowledgeIr.Flow> flows,
            List<KnowledgeIr.Conflict> conflicts,
            List<KnowledgeIr.Gap> gaps) {
        public KnowledgeDraft {
            rules = rules == null ? List.of() : List.copyOf(rules);
            flows = flows == null ? List.of() : List.copyOf(flows);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    record RuleDraft(String title, String condition, String conclusion, int priority,
            List<String> exceptions, List<String> sourceRefs) {
        public RuleDraft {
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
}
