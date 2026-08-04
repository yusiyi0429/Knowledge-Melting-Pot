package com.knowledgemeltingpot.workbench.application.port;

import java.util.UUID;

/** Stable application boundary for executing one released Skill against one Holdout input. */
public interface SkillEvaluationWorkflowPort {
    EvaluationPrediction predict(EvaluationRequest request);

    record EvaluationRequest(
            UUID evaluationRunId,
            UUID caseId,
            UUID modelConfigVersionId,
            UUID skillVersionId,
            String caseKey,
            String input) { }

    record EvaluationPrediction(String prediction) {
        public EvaluationPrediction {
            prediction = prediction == null ? "" : prediction.strip();
            if (prediction.isBlank() || prediction.length() > 500) {
                throw new IllegalArgumentException("evaluation prediction is invalid");
            }
        }
    }
}
