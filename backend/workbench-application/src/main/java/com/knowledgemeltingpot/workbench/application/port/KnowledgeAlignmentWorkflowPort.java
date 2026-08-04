package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.AlignmentAction;
import com.knowledgemeltingpot.workbench.domain.KnowledgeIr;
import java.util.List;
import java.util.UUID;

public interface KnowledgeAlignmentWorkflowPort {
    AlignmentResult generate(AlignmentRequest request);

    record AlignmentRequest(UUID jobId, AlignmentAction action, KnowledgeIr base,
            List<Evidence> evidence) {
        public AlignmentRequest {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record Evidence(KnowledgeIr.SourceRef sourceRef, String content) {
    }

    record AlignmentResult(KnowledgeIr replacement, String reason) {
    }
}
