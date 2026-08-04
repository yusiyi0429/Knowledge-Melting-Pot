package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.ExplorationCandidate;
import java.util.List;
import java.util.UUID;

/** Typed boundary for scene exploration; agent-core dynamic values never cross it. */
public interface SceneExplorationWorkflowPort {
    ExplorationResult explore(ExplorationRequest request);

    record ExplorationRequest(UUID sessionId, UUID modelConfigVersionId, UUID skillVersionId,
            List<ExplorationSource> sources) {
        public ExplorationRequest { sources = List.copyOf(sources); }
    }

    record ExplorationSource(UUID materialId, String fileName, List<ExplorationChunk> chunks) {
        public ExplorationSource { chunks = List.copyOf(chunks); }
    }

    record ExplorationChunk(String sourceRefCode, String locator, String content) { }

    record ExplorationResult(List<CandidateDraft> candidates) {
        public ExplorationResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    record CandidateDraft(int rank, String sceneName, String sceneDescription,
            String subSceneName, String subSceneDescription, String rationale,
            ExplorationCandidate.ValueLevel valueLevel, int estimatedRuleCount,
            int estimatedFlowCount, List<String> tags, List<UUID> materialIds) {
        public CandidateDraft {
            tags = tags == null ? List.of() : List.copyOf(tags);
            materialIds = materialIds == null ? List.of() : List.copyOf(materialIds);
        }
    }
}
