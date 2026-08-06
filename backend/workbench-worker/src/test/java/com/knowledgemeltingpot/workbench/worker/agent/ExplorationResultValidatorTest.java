package com.knowledgemeltingpot.workbench.worker.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgemeltingpot.workbench.application.port.SceneExplorationWorkflowPort;
import com.knowledgemeltingpot.workbench.domain.ExplorationCandidate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExplorationResultValidatorTest {
    private final ExplorationResultValidator validator = new ExplorationResultValidator();

    @Test
    void normalizesRankTagsCountsAndSourceCodesWithoutInventingEvidence() {
        var request = request();
        var draft = new SceneExplorationWorkflowPort.CandidateDraft(9, "  贷款  分类 ", " 说明 ",
                " 逾期 标签 ", " 子说明 ", " 依据  来源 ", ExplorationCandidate.ValueLevel.HIGH,
                -4, -2, List.of(" 风险 ", "风险", "", "超长标签".repeat(20)),
                List.of("mat-01", "MAT-01"));

        var result = validator.normalize(request,
                new SceneExplorationWorkflowPort.ExplorationResult(List.of(draft)));

        var normalized = result.candidates().getFirst();
        assertThat(normalized.rank()).isEqualTo(1);
        assertThat(normalized.sceneName()).isEqualTo("贷款 分类");
        assertThat(normalized.estimatedRuleCount()).isZero();
        assertThat(normalized.estimatedFlowCount()).isZero();
        assertThat(normalized.tags()).hasSize(2).allMatch(value -> value.length() <= 40);
        assertThat(normalized.sourceCodes()).containsExactly("MAT-01");
    }

    @Test
    void rejectsUnknownOrMissingEvidenceWithStableCodes() {
        assertThatThrownBy(() -> validator.normalize(request(), result(List.of("MAT-99"))))
                .isInstanceOfSatisfying(ExplorationResultValidator.ValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("EXPLORATION_SOURCE_REFERENCE_INVALID"));
        assertThatThrownBy(() -> validator.normalize(request(), result(List.of())))
                .isInstanceOfSatisfying(ExplorationResultValidator.ValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("EXPLORATION_SOURCE_REFERENCE_MISSING"));
    }

    private SceneExplorationWorkflowPort.ExplorationRequest request() {
        return new SceneExplorationWorkflowPort.ExplorationRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), List.of(new SceneExplorationWorkflowPort.ExplorationSource(
                        UUID.randomUUID(), "MAT-01", "制度.txt", List.of())));
    }

    private SceneExplorationWorkflowPort.ExplorationResult result(List<String> sources) {
        return new SceneExplorationWorkflowPort.ExplorationResult(List.of(
                new SceneExplorationWorkflowPort.CandidateDraft(1, "贷款分类", "", "逾期分类", "", "来源明确",
                        ExplorationCandidate.ValueLevel.MEDIUM, 1, 1, List.of(), sources)));
    }
}
