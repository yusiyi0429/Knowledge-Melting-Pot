package com.knowledgemeltingpot.workbench.worker.agent;

import com.knowledgemeltingpot.workbench.application.port.SceneExplorationWorkflowPort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Normalizes safe presentation noise and rejects unsupported scene claims. */
@Component
public class ExplorationResultValidator {
    private static final int MAX_CANDIDATES = 5;
    private static final int MAX_TAGS = 8;
    private static final int MAX_TAG_LENGTH = 40;

    public SceneExplorationWorkflowPort.ExplorationResult normalize(
            SceneExplorationWorkflowPort.ExplorationRequest request,
            SceneExplorationWorkflowPort.ExplorationResult result) {
        if (result == null || result.candidates().isEmpty()) {
            throw failure("EXPLORATION_CANDIDATES_EMPTY");
        }
        if (result.candidates().size() > MAX_CANDIDATES) {
            throw failure("EXPLORATION_CANDIDATE_LIMIT_EXCEEDED");
        }
        Set<String> allowedSources = request.sources().stream()
                .map(SceneExplorationWorkflowPort.ExplorationSource::sourceCode)
                .map(this::sourceCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<SceneExplorationWorkflowPort.CandidateDraft> normalized = java.util.stream.IntStream
                .range(0, result.candidates().size())
                .mapToObj(index -> normalizeCandidate(result.candidates().get(index), index + 1, allowedSources))
                .toList();
        return new SceneExplorationWorkflowPort.ExplorationResult(normalized);
    }

    private SceneExplorationWorkflowPort.CandidateDraft normalizeCandidate(
            SceneExplorationWorkflowPort.CandidateDraft draft, int rank, Set<String> allowedSources) {
        if (draft == null) throw failure("EXPLORATION_REQUIRED_FIELD_MISSING");
        String sceneName = required(draft.sceneName(), 200);
        String subSceneName = required(draft.subSceneName(), 200);
        String rationale = required(draft.rationale(), 2_000);
        if (draft.valueLevel() == null) throw failure("EXPLORATION_VALUE_LEVEL_INVALID");

        LinkedHashSet<String> sources = new LinkedHashSet<>();
        for (String value : draft.sourceCodes()) {
            String code = sourceCode(value);
            if (!allowedSources.contains(code)) throw failure("EXPLORATION_SOURCE_REFERENCE_INVALID");
            sources.add(code);
        }
        if (sources.isEmpty()) throw failure("EXPLORATION_SOURCE_REFERENCE_MISSING");

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String value : draft.tags()) {
            if (value == null || value.isBlank()) continue;
            String tag = compact(value);
            if (tag.length() > MAX_TAG_LENGTH) tag = tag.substring(0, MAX_TAG_LENGTH).strip();
            if (!tag.isBlank()) tags.add(tag);
            if (tags.size() == MAX_TAGS) break;
        }
        return new SceneExplorationWorkflowPort.CandidateDraft(rank, sceneName,
                optional(draft.sceneDescription(), 10_000), subSceneName,
                optional(draft.subSceneDescription(), 10_000), rationale, draft.valueLevel(),
                Math.max(0, draft.estimatedRuleCount()), Math.max(0, draft.estimatedFlowCount()),
                List.copyOf(tags), List.copyOf(sources));
    }

    private String required(String value, int max) {
        String normalized = compact(value);
        if (normalized.isBlank()) throw failure("EXPLORATION_REQUIRED_FIELD_MISSING");
        if (normalized.length() > max) throw failure("EXPLORATION_FIELD_TOO_LONG");
        return normalized;
    }

    private String optional(String value, int max) {
        String normalized = compact(value);
        if (normalized.length() > max) throw failure("EXPLORATION_FIELD_TOO_LONG");
        return normalized;
    }

    private String compact(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private String sourceCode(String value) {
        return compact(value).toUpperCase(Locale.ROOT);
    }

    private ValidationException failure(String code) {
        return new ValidationException(code);
    }

    public static final class ValidationException extends RuntimeException {
        private final String code;

        ValidationException(String code) {
            super("Scene exploration result validation failed");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
